package pe.jmaromas.api.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/admin/productos")
public class InventarioController {
    private final JdbcTemplate jdbc;
    private static final Pattern CODIGO = Pattern.compile("[A-Z0-9._-]{4,64}");
    private static final Pattern SOLO_DIGITOS = Pattern.compile("[0-9]+");

    public InventarioController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Registro(String codigoBarras, String nombre, String sku,
                           String marca, String descripcion, UUID categoriaId,
                           BigDecimal precio, Integer stock,
                           String publicoObjetivo, Integer contenidoMl,
                           String familiaOlfativa) {}
    public record IngresoStock(Integer cantidad, String motivo) {}

    @GetMapping
    public List<Map<String, Object>> listar() {
        return jdbc.queryForList("""
            select p.id, p.codigo_barras as "codigoBarras", p.sku, p.nombre, p.marca, p.descripcion,
                   p.categoria_id as "categoriaId", p.precio, p.stock, p.activo, p.publicado,
                   p.publico_objetivo as "publicoObjetivo", p.contenido_ml as "contenidoMl",
                   p.familia_olfativa as "familiaOlfativa",
                   (select i.ruta_storage from public.imagenes_producto i
                    where i.producto_id=p.id order by i.orden,i.creada_en,i.id limit 1) as "rutaFoto"
            from public.productos p order by p.creado_en desc limit 100
            """);
    }

    @GetMapping("/codigo-barras/{codigo}")
    public ResponseEntity<Map<String,Object>> buscar(@PathVariable String codigo) {
        String canonico = codigoNormalizado(codigo);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select p.id, p.codigo_barras as "codigoBarras", p.sku, p.nombre, p.marca, p.descripcion,
                   p.categoria_id as "categoriaId", p.precio, p.stock, p.activo, p.publicado,
                   p.publico_objetivo as "publicoObjetivo", p.contenido_ml as "contenidoMl",
                   p.familia_olfativa as "familiaOlfativa",
                   (select i.ruta_storage from public.imagenes_producto i
                    where i.producto_id=p.id order by i.orden,i.creada_en,i.id limit 1) as "rutaFoto"
            from public.productos p where p.codigo_barras = ? limit 1
            """, canonico);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.getFirst());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String,Object>> registrar(@RequestBody Registro data, HttpServletRequest request) {
        String codigo = codigoNormalizado(data.codigoBarras());
        if (data.nombre() == null || data.nombre().isBlank() || data.nombre().length() > 160
                || data.precio() == null || data.precio().signum() < 0
                || data.stock() == null || data.stock() < 0 || data.stock() > 1000000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nombre, precio o stock inválidos"));
        }
        String sku = data.sku() == null || data.sku().isBlank()
                ? "BAR-" + codigo : data.sku().strip().toUpperCase(Locale.ROOT);
        if (sku.length() > 90 || sku.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SKU inválido"));
        }
        validarPresentacion(data.publicoObjetivo(),data.contenidoMl(),data.familiaOlfativa());
        UUID id = UUID.randomUUID();
        String slug = "producto-" + id.toString();
        try {
            int rows = jdbc.update("""
                insert into public.productos
                    (id, codigo_barras, sku, slug, nombre, marca, descripcion, categoria_id,
                     precio, stock, publico_objetivo, contenido_ml, familia_olfativa, activo, publicado)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, false)
                """, id, codigo, sku, slug, data.nombre().strip(),
                blankToNull(data.marca()), blankToNull(data.descripcion()),
                data.categoriaId(), data.precio(), data.stock(),
                data.publicoObjetivo(),data.contenidoMl(),data.familiaOlfativa());
            if (rows != 1) throw new IllegalStateException("No se insertó el producto");
            if (data.stock() > 0) {
                jdbc.update("""
                    insert into public.movimientos_stock
                        (producto_id, cantidad_delta, stock_anterior, stock_nuevo, motivo, realizado_por)
                    values (?, ?, 0, ?, 'STOCK_INICIAL', ?)
                    """, id, data.stock(), data.stock(), (UUID)request.getAttribute("adminUserId"));
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "codigoBarras", codigo,
                "sku", sku, "stock", data.stock(), "publicado", false));
        } catch (DataIntegrityViolationException e) {
            // La restricción UNIQUE de PostgreSQL protege contra dos móviles escaneando a la vez.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Código de barras o SKU ya registrado. Consulta el producto antes de continuar."));
        }
    }


    /** Stock no se modifica aquí: usar /stock para mantener movimientos auditables. */
    public record Edicion(String codigoBarras,String sku,String nombre,
                          String marca,String descripcion,BigDecimal precio,
                          String publicoObjetivo,Integer contenidoMl,String familiaOlfativa) {}

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String,Object>> editar(@PathVariable UUID id,@RequestBody Edicion data) {
        if(data==null)throw new IllegalArgumentException("Datos del producto requeridos");
        String codigo=codigoNormalizado(data.codigoBarras());
        if(data.nombre()==null || data.nombre().isBlank() || data.nombre().length()>160 ||
           data.precio()==null || data.precio().signum()<0 ||
           data.precio().scale()>2 || data.precio().precision()>12)
            throw new IllegalArgumentException("Nombre o precio inválidos");
        if(data.sku()==null || data.sku().isBlank() || data.sku().length()>90)
            throw new IllegalArgumentException("SKU inválido");
        if(data.marca()!=null && data.marca().length()>160)
            throw new IllegalArgumentException("Marca demasiado larga");
        if(data.descripcion()!=null && data.descripcion().length()>4000)
            throw new IllegalArgumentException("Descripción demasiado larga");
        validarPresentacion(data.publicoObjetivo(),data.contenidoMl(),data.familiaOlfativa());
        List<Integer> existentes=jdbc.query("select stock from public.productos where id=? for update",
            (rs,n)->rs.getInt(1),id);
        if(existentes.isEmpty())return ResponseEntity.notFound().build();
        Boolean imposible=jdbc.queryForObject("""
            select exists(select 1 from public.descuentos
              where producto_id=? and activo=true and tipo='MONTO' and valor>=?)
            """,Boolean.class,id,data.precio());
        if(Boolean.TRUE.equals(imposible))
            throw new IllegalArgumentException(
                "El precio debe superar el descuento fijo activo. Desactívalo primero.");
        try {
            jdbc.update("""
                update public.productos set codigo_barras=?,sku=?,nombre=?,marca=?,
                descripcion=?,precio=?,publico_objetivo=?,contenido_ml=?,
                familia_olfativa=?,actualizado_en=now()
                where id=?
                """,codigo,data.sku().strip().toUpperCase(Locale.ROOT),
                data.nombre().strip(),blankToNull(data.marca()),blankToNull(data.descripcion()),
                data.precio(),data.publicoObjetivo(),data.contenidoMl(),
                data.familiaOlfativa(),id);
        }catch(DataIntegrityViolationException e){
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of("error","Este código de barras o SKU ya existe"));
        }
        return ResponseEntity.ok(Map.of("id",id,"mensaje","Producto actualizado"));
    }

    private static void validarPresentacion(String publico,Integer ml,String familia) {
        if(publico!=null && !List.of("HOMBRE","MUJER","UNISEX").contains(publico))
            throw new IllegalArgumentException("Selecciona hombre, mujer o unisex");
        if(ml!=null && (ml<1 || ml>2000))
            throw new IllegalArgumentException("La presentación debe ser entre 1 y 2000 ml");
        if(familia!=null && !List.of("Floral","Amaderada","Cítrica",
            "Fresca","Oriental","Frutal").contains(familia))
            throw new IllegalArgumentException("Familia olfativa no admitida");
    }

    public record Publicacion(boolean publicado) {}

    @PatchMapping("/{id}/publicacion")
    @Transactional
    public ResponseEntity<Map<String,Object>> publicar(@PathVariable UUID id,
         @RequestBody Publicacion body) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
           select activo,stock,publicado from public.productos where id=? for update
           """,id);
        if(rows.isEmpty())return ResponseEntity.notFound().build();
        if(body.publicado() && (!Boolean.TRUE.equals(rows.getFirst().get("activo")) ||
             ((Number)rows.getFirst().get("stock")).intValue()<1))
             return ResponseEntity.badRequest().body(Map.of(
                "error","Para publicar, el producto debe estar activo y tener stock"));
        jdbc.update("update public.productos set publicado=?,actualizado_en=now() where id=?",
            body.publicado(),id);
        return ResponseEntity.ok(Map.of("id",id,"publicado",body.publicado()));
    }

    @PatchMapping("/{id}/stock")
    @Transactional
    public ResponseEntity<Map<String,Object>> ingresar(@PathVariable UUID id,
                                                         @RequestBody IngresoStock data,
                                                         HttpServletRequest request) {
        if (data.cantidad() == null || data.cantidad() < 1 || data.cantidad() > 100000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cantidad de ingreso inválida"));
        }
        List<Integer> previos = jdbc.query("""
            select stock from public.productos where id = ? for update
            """, (rs, num) -> rs.getInt(1), id);
        if (previos.isEmpty()) return ResponseEntity.notFound().build();
        int anterior = previos.getFirst();
        if ((long) anterior + data.cantidad() > Integer.MAX_VALUE) {
            return ResponseEntity.badRequest().body(Map.of("error", "Stock fuera de rango"));
        }
        int nuevo = anterior + data.cantidad();
        jdbc.update("update public.productos set stock=?, actualizado_en=now() where id=?",
                nuevo, id);
        String motivo = "INGRESO_ESCANER";
        if (data.motivo() != null && !data.motivo().isBlank()) {
            motivo = "INGRESO_ESCANER: " + data.motivo().strip().substring(0,
                Math.min(120, data.motivo().strip().length()));
        }
        jdbc.update("""
            insert into public.movimientos_stock
                (producto_id, cantidad_delta, stock_anterior, stock_nuevo, motivo, realizado_por)
            values (?, ?, ?, ?, ?, ?)
            """, id, data.cantidad(), anterior, nuevo, motivo,
            (UUID)request.getAttribute("adminUserId"));
        return ResponseEntity.ok(Map.of("id", id, "stockAnterior", anterior, "stockNuevo", nuevo));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
    private static String codigoNormalizado(String text) {
        if (text == null) throw new IllegalArgumentException("Código requerido");
        String codigo = text.strip().toUpperCase(Locale.ROOT);
        if (SOLO_DIGITOS.matcher(codigo).matches() && codigo.length() == 12)
            codigo = "0" + codigo; // UPC-A y EAN-13 con cero inicial se refieren al mismo GTIN.
        if (!CODIGO.matcher(codigo).matches())
            throw new IllegalArgumentException("Formato no admitido");
        if (SOLO_DIGITOS.matcher(codigo).matches()) {
            if (!(codigo.length() == 8 || codigo.length() == 13 || codigo.length() == 14)
                    || !checksumGtin(codigo))
                throw new IllegalArgumentException("GTIN sin dígito verificador válido");
        }
        return codigo;
    }
    private static boolean checksumGtin(String value) {
        int suma = 0;
        for (int i = value.length() - 2, p = 0; i >= 0; i--, p++) {
            suma += (value.charAt(i) - '0') * (p % 2 == 0 ? 3 : 1);
        }
        return (10 - (suma % 10)) % 10 == value.charAt(value.length()-1) - '0';
    }
}
