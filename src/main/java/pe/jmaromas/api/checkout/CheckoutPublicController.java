package pe.jmaromas.api.checkout;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutPublicController {
    private final JdbcTemplate jdbc;
    @Value("${app.checkout.enabled:false}")
    private boolean checkoutEnabled;
    // La coordinación por WhatsApp no habilita cobros web ni comprobantes.
    @Value("${app.checkout.whatsapp-enabled:true}")
    private boolean whatsappEnabled;
    private final RestClient auth;
    private final String publishableKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CheckoutPublicController(
        JdbcTemplate jdbc,
        @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String url,
        @Value("${app.supabase.publishable-key:}") String publishableKey
    ) {
        this.jdbc=jdbc;
        this.publishableKey=publishableKey;
        this.auth=RestClient.builder().baseUrl(url).build();
    }

    public record Item(UUID productoId, Integer cantidad) {}
    public record Cliente(String nombre, String dni, String telefono, String email,
        String departamento, String provincia, String distrito,
        String direccion, String referencia) {}
    public record PedidoSolicitud(UUID idempotencia, String metodoPago, UUID medioPagoId,
        Cliente cliente, List<Item> articulos, boolean guardarDatos) {}

    /** Precios y artículos enviados por el servidor: el cliente no define los importes. */
    public record ResumenArticulo(String nombre, String sku, int cantidad,
                                  BigDecimal precioUnitario, BigDecimal totalLinea) {}

    @GetMapping("/medios-pago")
    public List<Map<String,Object>> mediosPago() {
        return jdbc.query("""
          select id, tipo, titulo, titular, numero_yape, qr_url, banco,
                 numero_cuenta, cci, telefono_whatsapp
          from app_private.medios_pago
          where activo=true order by tipo, orden, creado_en
          """,(rs, i)->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",rs.getObject("id",UUID.class));
            m.put("tipo",rs.getString("tipo"));
            m.put("titulo",rs.getString("titulo"));
            m.put("titular",rs.getString("titular"));
            m.put("numeroYape",rs.getString("numero_yape"));
            m.put("qrUrl",rs.getString("qr_url"));
            m.put("banco",rs.getString("banco"));
            m.put("numeroCuenta",rs.getString("numero_cuenta"));
            m.put("cci",rs.getString("cci"));
            m.put("telefonoWhatsApp",rs.getString("telefono_whatsapp"));
            return m;
          });
    }

    @PostMapping("/pedidos")
    @Transactional
    public ResponseEntity<Map<String,Object>> crear(@RequestBody PedidoSolicitud data,
         @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        if(data==null) throw new IllegalArgumentException("Solicitud de pedido requerida");
        boolean whatsapp="COORDINAR_WHATSAPP".equals(data.metodoPago());
        if(whatsapp ? !whatsappEnabled : !checkoutEnabled)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                whatsapp ? "Los pedidos por WhatsApp están temporalmente deshabilitados"
                         : "Los pagos web todavía no están habilitados");
        // Idempotencia antes de reservar stock: el mismo UUID jamás debe descontar 2 veces.
        if(data.idempotencia()==null)
            throw new IllegalArgumentException("Identificador de operación obligatorio");
        Boolean duplicado=jdbc.queryForObject("""
          select exists(select 1 from public.pedidos where idempotencia=?)
          """,Boolean.class,data.idempotencia());
        if(Boolean.TRUE.equals(duplicado))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Este pedido ya fue registrado. No vuelvas a generarlo; revisa el seguimiento.");
        Cliente c=data.cliente();
        validarCliente(c);
        if(data.idempotencia()==null) throw new IllegalArgumentException("Identificador de operación obligatorio");
        if(data.articulos()==null || data.articulos().isEmpty() || data.articulos().size()>30)
            throw new IllegalArgumentException("El carrito debe tener entre 1 y 30 líneas");
        String metodo=data.metodoPago();
        if(!List.of("YAPE","TRANSFERENCIA","COORDINAR_WHATSAPP").contains(metodo))
            throw new IllegalArgumentException("Método de pago no permitido");
        UUID idMedio=elegirMedio(metodo,data.medioPagoId());
        UUID usuario=usuarioOpcional(authorization);
        // Ventana antifraude en PostgreSQL, compartida entre instancias de Railway.
        // Lock por teléfono normalizado evita que múltiples solicitudes paralelas lo eludan.
        if(whatsapp) limitarPedidosWhatsApp(c);
        Map<UUID,Integer> cantidades=new TreeMap<>();
        for(Item item:data.articulos()){
            if(item==null || item.productoId()==null || item.cantidad()==null ||
                    item.cantidad()<1 || item.cantidad()>30)
                throw new IllegalArgumentException("Producto o cantidad inválidos");
            cantidades.merge(item.productoId(),item.cantidad(),Integer::sum);
        }
        if(cantidades.size()>20 || cantidades.values().stream().anyMatch(v->v>30))
            throw new IllegalArgumentException("Límite de productos por pedido excedido");

        List<Linea> lineas=new ArrayList<>();
        BigDecimal subtotal=BigDecimal.ZERO;
        BigDecimal descuentoTotal=BigDecimal.ZERO;
        // Locks en orden de UUID para evitar interbloqueos entre compras simultáneas.
        for(var entrada:cantidades.entrySet()){
            List<Linea> filas=jdbc.query("""
              select p.id,p.sku,p.nombre,p.precio,p.stock,
                     coalesce((select case when d.tipo='PORCENTAJE'
                                       then round(p.precio*d.valor/100,2)
                                       else d.valor end
                       from public.descuentos d
                       where d.producto_id=p.id and d.activo=true
                         and now()>=d.inicio and now()<d.fin
                       order by d.inicio desc,d.id desc limit 1),0) as rebaja
              from public.productos p
              where p.id=? and p.activo=true and p.publicado=true for update of p
              """,(rs,n)->new Linea(
                  rs.getObject("id",UUID.class),rs.getString("sku"),
                  rs.getString("nombre"),rs.getBigDecimal("precio"),
                  rs.getBigDecimal("rebaja"),
                  rs.getInt("stock"),entrada.getValue()),entrada.getKey());
            if(filas.isEmpty())throw new IllegalArgumentException("Producto no disponible: "+entrada.getKey());
            Linea linea=filas.getFirst();
            if(linea.stock()<linea.cantidad())
                throw new IllegalArgumentException("Stock insuficiente para "+linea.nombre());
            BigDecimal cantidad=BigDecimal.valueOf(linea.cantidad());
            subtotal=subtotal.add(linea.precio().multiply(cantidad));
            descuentoTotal=descuentoTotal.add(linea.rebaja().multiply(cantidad));
            lineas.add(linea);
        }
        BigDecimal totalConDescuento=subtotal.subtract(descuentoTotal);
        if(totalConDescuento.signum()<1)
            throw new IllegalArgumentException("El pedido debe tener un monto positivo");
        UUID pedidoId=UUID.randomUUID();
        String codigo="JMA-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+
            pedidoId.toString().substring(0,8).toUpperCase(Locale.ROOT);
        byte[] secreto=new byte[32];
        secureRandom.nextBytes(secreto);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(secreto);
        String hash=sha256(token);
        // Total de productos; costo de envío aún NO cotizado y por tanto no cobrado.
        jdbc.update("""
          insert into public.pedidos
          (id,codigo,usuario_id,idempotencia,cliente_snapshot,direccion_snapshot,
           metodo_pago,medio_pago_id,estado,estado_pago,
           subtotal,descuento_total,costo_entrega,total,acceso_invitado_hash,expira_en)
          values (?,?,?,?,
            jsonb_build_object('nombre',?,'dni',?,'telefono',?,'email',?),
            jsonb_build_object('destinatario',?,'telefono',?,'departamento',?,'provincia',?,
                               'distrito',?,'linea_direccion',?,'referencia',?),
            ?,?,'PENDIENTE','PENDIENTE',?,?,0,?,?,now()+interval '24 hours')
          """,
          pedidoId,codigo,usuario,data.idempotencia(),
          c.nombre().strip(),c.dni(),c.telefono().strip(),c.email().strip().toLowerCase(Locale.ROOT),
          c.nombre().strip(),c.telefono().strip(),c.departamento().strip(),
          c.provincia().strip(),c.distrito().strip(),c.direccion().strip(),limpiar(c.referencia()),
          metodo,idMedio,subtotal,descuentoTotal,totalConDescuento,hash);

        for(Linea l:lineas){
            BigDecimal totalLinea=l.precio().subtract(l.rebaja())
                .multiply(BigDecimal.valueOf(l.cantidad()));
            jdbc.update("""
                insert into public.detalles_pedido
                (pedido_id,producto_id,sku_snapshot,nombre_snapshot,cantidad,
                 precio_unitario,descuento_unitario,total_linea)
                values(?,?,?,?,?,?,?,?)
                """,pedidoId,l.id(),l.sku(),l.nombre(),l.cantidad(),l.precio(),l.rebaja(),totalLinea);
            jdbc.update("update public.productos set stock=stock-?, actualizado_en=now() where id=?",
                l.cantidad(),l.id());
            jdbc.update("""
                insert into public.movimientos_stock
                (producto_id,pedido_id,cantidad_delta,stock_anterior,stock_nuevo,motivo)
                values(?,?,?,?,?,'RESERVA_PEDIDO')
                """,l.id(),pedidoId,-l.cantidad(),l.stock(),l.stock()-l.cantidad());
        }
        jdbc.update("""
          insert into public.historial_pedido(pedido_id,estado_nuevo,observacion)
          values(?,'PENDIENTE','Pedido creado; stock reservado durante 24 horas')
          """,pedidoId);

        if(usuario!=null && data.guardarDatos()){
            jdbc.update("""
                insert into public.perfiles(usuario_id,nombre,telefono,dni)
                values(?,?,?,?)
                on conflict(usuario_id) do update set
                nombre=excluded.nombre,telefono=excluded.telefono,
                dni=excluded.dni,actualizado_en=now()
                """,usuario,c.nombre().strip(),c.telefono().strip(),c.dni());
            jdbc.update("""
                insert into public.direcciones
                (usuario_id,destinatario,telefono,departamento,provincia,distrito,linea_direccion,referencia)
                values(?,?,?,?,?,?,?,?)
                """,usuario,c.nombre().strip(),c.telefono().strip(),c.departamento().strip(),
                c.provincia().strip(),c.distrito().strip(),c.direccion().strip(),limpiar(c.referencia()));
        }

        Map<String,Object> respuesta=new LinkedHashMap<>();
        respuesta.put("id",pedidoId);
        respuesta.put("codigo",codigo);
        respuesta.put("tokenConsulta",token);
        respuesta.put("estado","PENDIENTE");
        respuesta.put("estadoPago","PENDIENTE");
        respuesta.put("metodoPago",metodo);
        respuesta.put("totalProductos",totalConDescuento);
        respuesta.put("subtotal",subtotal);
        respuesta.put("descuentoTotal",descuentoTotal);
        respuesta.put("envio","POR_COORDINAR");
        respuesta.put("reservaHoras",24);
        if(whatsapp) {
            respuesta.put("articulos",lineas.stream().map(l -> new ResumenArticulo(
                l.nombre(),l.sku(),l.cantidad(),l.precio().subtract(l.rebaja()),
                l.precio().subtract(l.rebaja()).multiply(BigDecimal.valueOf(l.cantidad()))
            )).toList());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }

    @GetMapping("/pedidos/{id}")
    public Map<String,Object> consultar(@PathVariable UUID id,
        @RequestHeader(value="X-Pedido-Token",required=false) String token) {
        validarAccesoPedido(id,token);
        return jdbc.queryForMap("""
          select codigo,estado,estado_pago as "estadoPago",metodo_pago as "metodoPago",
          total as "totalProductos", creado_en as "creadoEn", expira_en as "expiraEn"
          from public.pedidos where id=?
          """,id);
    }

    public void validarAccesoPedido(UUID id,String token) {
        if(token==null || token.length()<32 || token.length()>256)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Token del pedido requerido");
        Boolean valido=jdbc.queryForObject("""
           select exists(select 1 from public.pedidos
             where id=? and acceso_invitado_hash=?)
           """,Boolean.class,id,sha256(token));
        if(!Boolean.TRUE.equals(valido))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No autorizado para consultar este pedido");
    }

    private void limitarPedidosWhatsApp(Cliente cliente) {
        String telefono=cliente.telefono().replaceAll("[^0-9]","");
        if(telefono.length()<9 || telefono.length()>15)
            throw new IllegalArgumentException("Indica un teléfono válido de 9 a 15 dígitos");
        // Serialización de solicitudes del mismo número dentro de esta transacción.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {},telefono);
        Map<String,Object> filas=jdbc.queryForMap("""
            select count(*) filter (where creado_en >= now()-interval '1 hour') as hora,
                   count(*) as dia
            from public.pedidos
            where creado_en >= now()-interval '24 hours'
              and regexp_replace(cliente_snapshot->>'telefono','[^0-9]','','g')=?
            """,telefono);
        long hora=((Number)filas.get("hora")).longValue();
        long dia=((Number)filas.get("dia")).longValue();
        if(hora>=2 || dia>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Se alcanzó el límite de pedidos para este teléfono. Intenta más tarde.");
    }

    private UUID elegirMedio(String metodo,UUID id) {
        String tipo=metodo.equals("COORDINAR_WHATSAPP")?"WHATSAPP":metodo;
        if(id==null) {
            if(!metodo.equals("COORDINAR_WHATSAPP"))
                throw new IllegalArgumentException("Debes seleccionar un medio de pago válido");
            List<UUID> opciones=jdbc.query("""
              select id from app_private.medios_pago where activo=true and tipo='WHATSAPP'
              order by orden limit 1
              """,(rs,n)->rs.getObject("id",UUID.class));
            if(opciones.isEmpty()) throw new IllegalArgumentException("WhatsApp aún no está configurado");
            return opciones.getFirst();
        }
        Boolean disponible=jdbc.queryForObject("""
          select exists(select 1 from app_private.medios_pago
             where id=? and tipo=? and activo=true)
          """,Boolean.class,id,tipo);
        if(!Boolean.TRUE.equals(disponible))
            throw new IllegalArgumentException("Medio de pago deshabilitado");
        return id;
    }

    private UUID usuarioOpcional(String authorization) {
        if(authorization==null || authorization.isBlank()) return null;
        if(!authorization.startsWith("Bearer ") || publishableKey.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no válida");
        try{
            @SuppressWarnings("unchecked")
            Map<String,Object> usuario=auth.get().uri("/auth/v1/user")
                .header("apikey",publishableKey)
                .header(HttpHeaders.AUTHORIZATION,authorization)
                .accept(MediaType.APPLICATION_JSON).retrieve()
                .onStatus(HttpStatusCode::isError,(req,res)->{
                    throw new IllegalArgumentException("Sesión rechazada");
                }).body(Map.class);
            if(usuario==null || !(usuario.get("id") instanceof String userId))
                throw new IllegalArgumentException("Usuario no encontrado");
            return UUID.fromString(userId);
        }catch(Exception e){
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Inicia sesión nuevamente");
        }
    }

    private void validarCliente(Cliente c) {
        if(c==null || !textoValido(c.nombre(),3,150) ||
           c.dni()==null || !c.dni().matches("[0-9]{8}") ||
           !textoValido(c.telefono(),8,20) ||
           !textoValido(c.email(),5,160) || !c.email().contains("@") ||
           !textoValido(c.departamento(),2,100) ||
           !textoValido(c.provincia(),2,100) ||
           !textoValido(c.distrito(),2,100) ||
           !textoValido(c.direccion(),5,250) ||
           (c.referencia()!=null && c.referencia().length()>250))
            throw new IllegalArgumentException("Revisa nombre, DNI (8 dígitos), contacto y dirección");
    }
    private boolean textoValido(String s,int min,int max) {
        return s!=null && s.strip().length()>=min && s.strip().length()<=max;
    }
    private String limpiar(String s){return s==null||s.isBlank()?null:s.strip();}
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 no disponible",e); }
    }
    private record Linea(UUID id,String sku,String nombre,BigDecimal precio,
                         BigDecimal rebaja,int stock,int cantidad){}
}
