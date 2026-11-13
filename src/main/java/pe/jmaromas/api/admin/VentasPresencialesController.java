package pe.jmaromas.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/ventas-presenciales")
public class VentasPresencialesController {
    private final JdbcTemplate jdbc;
    public VentasPresencialesController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Item(UUID productoId, Integer cantidad) {}
    public record NuevaVenta(UUID idempotencia, String medioPago, List<Item> articulos) {}
    private record Producto(UUID id, String sku, String nombre, BigDecimal precio, BigDecimal costoCompra, int stock, int cantidad) {}

    @GetMapping("/productos")
    public List<Map<String,Object>> productos(@RequestParam(defaultValue="") String buscar) {
        String q = buscar.strip();
        if (q.length() > 120) throw new IllegalArgumentException("Búsqueda demasiado larga");
        return jdbc.queryForList("""
            select id, sku, codigo_barras as "codigoBarras", nombre, marca, precio, stock
            from public.productos
            where activo=true
              and (?='' or nombre ilike '%' || ? || '%' or sku ilike '%' || ? || '%'
                   or codigo_barras ilike '%' || ? || '%')
            order by nombre limit 50
            """, q,q,q,q);
    }

    /** Historial administrativo de ventas físicas, sin datos personales de clientes. */
    @GetMapping
    public List<Map<String,Object>> listar(
            @RequestParam(required=false) String desde,
            @RequestParam(required=false) String hasta,
            @RequestParam(defaultValue="") String buscar,
            @RequestParam(defaultValue="TODOS") String medioPago) {
        String q=buscar.strip();
        if(q.length()>120)throw new IllegalArgumentException("Búsqueda demasiado larga");
        if(!List.of("TODOS","EFECTIVO","YAPE","TRANSFERENCIA","TARJETA").contains(medioPago))
            throw new IllegalArgumentException("Medio de pago inválido");
        java.time.LocalDate fechaDesde=desde==null||desde.isBlank()?null:java.time.LocalDate.parse(desde);
        java.time.LocalDate fechaHasta=hasta==null||hasta.isBlank()?null:java.time.LocalDate.parse(hasta);
        if(fechaDesde!=null && fechaHasta!=null && fechaDesde.isAfter(fechaHasta))
            throw new IllegalArgumentException("El inicio debe ser anterior al fin");
        // Limitar la consulta para evitar cargar indefinidamente el historial.
        var zone=ZoneId.of("America/Lima");
        OffsetDateTime inicio=fechaDesde==null?null:fechaDesde.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime fin=fechaHasta==null?null:fechaHasta.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return jdbc.queryForList("""
            select id,codigo,medio_pago as "medioPago",total,creado_en as "creadoEn"
            from app_private.ventas_presenciales
            where (?::timestamptz is null or creado_en>=?::timestamptz)
              and (?::timestamptz is null or creado_en<?::timestamptz)
              and (?='' or codigo ilike '%' || ? || '%')
              and (?='TODOS' or medio_pago=?)
            order by creado_en desc,id desc limit 500
            """,inicio,inicio,fin,fin,q,q,medioPago,medioPago);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String,Object>> consultar(@PathVariable UUID id) {
        var rows=jdbc.queryForList("""
            select id,codigo,medio_pago as "medioPago",total,creado_en as "creadoEn"
            from app_private.ventas_presenciales where id=?
            """,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        Map<String,Object> resultado=new LinkedHashMap<>(rows.getFirst());
        resultado.put("articulos",jdbc.queryForList("""
            select sku_snapshot as sku,nombre_snapshot as nombre,cantidad,
                   precio_unitario as "precioUnitario",total_linea as "totalLinea"
            from app_private.ventas_presenciales_detalle where venta_id=?
            order by id
            """,id));
        return ResponseEntity.ok(resultado);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String,Object>> crear(@RequestBody NuevaVenta data, HttpServletRequest request) {
        if(data==null || data.idempotencia()==null)
            throw new IllegalArgumentException("Identificador de venta requerido");
        if(data.medioPago()==null ||
                !List.of("EFECTIVO","YAPE","TRANSFERENCIA","TARJETA").contains(data.medioPago()))
            throw new IllegalArgumentException("Selecciona un medio de pago válido");
        if(data.articulos()==null || data.articulos().isEmpty() || data.articulos().size()>40)
            throw new IllegalArgumentException("Agrega entre 1 y 40 líneas de productos");
        TreeMap<UUID,Integer> cantidades=new TreeMap<>();
        for(Item item:data.articulos()) {
            if(item==null || item.productoId()==null || item.cantidad()==null ||
                    item.cantidad()<1 || item.cantidad()>1000)
                throw new IllegalArgumentException("Cantidad inválida");
            cantidades.merge(item.productoId(),item.cantidad(),Integer::sum);
        }
        if(cantidades.size()>40 || cantidades.values().stream().anyMatch(n->n>1000))
            throw new IllegalArgumentException("Se excedió la cantidad permitida");

        // Doble clic o reintento: nunca generar dos ventas con la misma clave.
        Boolean repetida=jdbc.queryForObject("""
            select exists(select 1 from app_private.ventas_presenciales where idempotencia=?)
            """,Boolean.class,data.idempotencia());
        if(Boolean.TRUE.equals(repetida))
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error","Esta venta ya está registrada. No vuelvas a cobrarla."));

        List<Producto> productos=new ArrayList<>();
        BigDecimal total=BigDecimal.ZERO;
        // Bloqueos en orden estable: compatible con reservas del e-commerce.
        for(var entrada:cantidades.entrySet()) {
            var encontrados=jdbc.query("""
                select p.id,p.sku,p.nombre,p.precio,c.costo_compra,p.stock
                from public.productos p
                left join app_private.productos_costos c on c.producto_id=p.id
                where p.id=? and p.activo=true for update of p
                """,(rs,n)->new Producto(rs.getObject("id",UUID.class),
                    rs.getString("sku"),rs.getString("nombre"),
                    rs.getBigDecimal("precio"),rs.getBigDecimal("costo_compra"),
                    rs.getInt("stock"),entrada.getValue()),
                entrada.getKey());
            if(encontrados.isEmpty())
                throw new IllegalArgumentException("Producto no disponible: "+entrada.getKey());
            Producto p=encontrados.getFirst();
            if(p.stock()<p.cantidad())
                throw new IllegalArgumentException("Stock insuficiente para "+p.nombre());
            if(p.precio()==null || p.precio().signum()<0)
                throw new IllegalArgumentException("Precio inválido: "+p.nombre());
            total=total.add(p.precio().multiply(BigDecimal.valueOf(p.cantidad())));
            productos.add(p);
        }
        if(total.signum()<=0) throw new IllegalArgumentException("La venta debe superar S/ 0");
        UUID ventaId=UUID.randomUUID();
        String codigo="VP-"+ventaId.toString().substring(0,8).toUpperCase();
        jdbc.update("""
            insert into app_private.ventas_presenciales
            (id,idempotencia,codigo,medio_pago,total,registrado_por)
            values(?,?,?,?,?,?)
            """,ventaId,data.idempotencia(),codigo,data.medioPago(),total,
            (UUID)request.getAttribute("adminUserId"));

        for(Producto p:productos) {
            BigDecimal linea=p.precio().multiply(BigDecimal.valueOf(p.cantidad()));
            jdbc.update("""
                insert into app_private.ventas_presenciales_detalle
                (venta_id,producto_id,sku_snapshot,nombre_snapshot,cantidad,precio_unitario,total_linea,costo_unitario_snapshot)
                values(?,?,?,?,?,?,?,?)
                """,ventaId,p.id(),p.sku(),p.nombre(),p.cantidad(),p.precio(),linea,p.costoCompra());
            jdbc.update("""
                update public.productos set stock=stock-?, actualizado_en=now() where id=?
                """,p.cantidad(),p.id());
            jdbc.update("""
                insert into public.movimientos_stock
                (producto_id,cantidad_delta,stock_anterior,stock_nuevo,motivo,realizado_por)
                values(?,?,?,?,?,?)
                """,p.id(),-p.cantidad(),p.stock(),p.stock()-p.cantidad(),
                "VENTA_PRESENCIAL:"+codigo,(UUID)request.getAttribute("adminUserId"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(consultar(ventaId).getBody());
    }
}
