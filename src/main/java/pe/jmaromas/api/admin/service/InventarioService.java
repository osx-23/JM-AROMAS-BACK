package pe.jmaromas.api.admin.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import pe.jmaromas.api.admin.domain.CodigoBarras;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class InventarioService {
    private final JdbcTemplate jdbc;

    public InventarioService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Registro(String codigoBarras, String nombre, String sku,
                           String marca, String descripcion, UUID categoriaId,
                           BigDecimal precio, Integer stock,
                           String publicoObjetivo, Integer contenidoMl,
                           String familiaOlfativa, BigDecimal precioCompra) {}
    public record IngresoStock(Integer cantidad, String motivo) {}

    public List<Map<String, Object>> listar() {
        return jdbc.queryForList("""
            select p.id, p.codigo_barras as "codigoBarras", p.sku, p.nombre, p.marca, p.descripcion,
                   p.categoria_id as "categoriaId", p.precio, pc.precio_compra as "precioCompra", p.stock, p.activo, p.publicado,
                   p.publico_objetivo as "publicoObjetivo", p.contenido_ml as "contenidoMl",
                   p.familia_olfativa as "familiaOlfativa",
                   (select i.ruta_storage from public.imagenes_producto i
                    where i.producto_id=p.id order by i.orden,i.creada_en,i.id limit 1) as "rutaFoto"
            from public.productos p left join app_private.productos_costos pc on pc.producto_id=p.id order by p.creado_en desc limit 100
            """);
    }

    public ResponseEntity<Map<String,Object>> buscar(String codigo) {
        String canonico = CodigoBarras.normalizar(codigo);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select p.id, p.codigo_barras as "codigoBarras", p.sku, p.nombre, p.marca, p.descripcion,
                   p.categoria_id as "categoriaId", p.precio, pc.precio_compra as "precioCompra", p.stock, p.activo, p.publicado,
                   p.publico_objetivo as "publicoObjetivo", p.contenido_ml as "contenidoMl",
                   p.familia_olfativa as "familiaOlfativa",
                   (select i.ruta_storage from public.imagenes_producto i
                    where i.producto_id=p.id order by i.orden,i.creada_en,i.id limit 1) as "rutaFoto"
            from public.productos p left join app_private.productos_costos pc on pc.producto_id=p.id where p.codigo_barras = ? limit 1
            """, canonico);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.getFirst());
    }

    @Transactional
    public ResponseEntity<Map<String,Object>> registrar(Registro data, HttpServletRequest request) {
        String codigo = CodigoBarras.normalizar(data.codigoBarras());
        if (data.nombre() == null || data.nombre().isBlank() || data.nombre().length() > 160
                || data.precio() == null || data.precio().signum() < 0
                || !precioCompraValido(data.precioCompra())
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
            guardarPrecioCompra(id, data.precioCompra());
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

    @Transactional
    public ResponseEntity<Map<String,Object>> editar(UUID id,Edicion data) {
        if(data==null)throw new IllegalArgumentException("Datos del producto requeridos");
        String codigo=CodigoBarras.normalizar(data.codigoBarras());
        if(data.nombre()==null || data.nombre().isBlank() || data.nombre().length()>160 ||
           data.precio()==null || data.precio().signum()<0 ||
           !precioCompraValido(data.precioCompra()) ||
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
        guardarPrecioCompra(id, data.precioCompra());
        return ResponseEntity.ok(Map.of("id",id,"mensaje","Producto actualizado"));
    }

    private static boolean precioCompraValido(BigDecimal precioCompra) {
        return precioCompra == null || (precioCompra.signum() >= 0 &&
            precioCompra.scale() <= 2 && precioCompra.precision() <= 12);
    }

    private void guardarPrecioCompra(UUID productoId, BigDecimal precioCompra) {
        jdbc.update("""
            insert into app_private.productos_costos(producto_id, precio_compra)
            values (?, ?)
            on conflict (producto_id) do update
              set precio_compra=excluded.precio_compra, actualizado_en=now()
            """, productoId, precioCompra);
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

    @Transactional
    public ResponseEntity<Map<String,Object>> publicar(UUID id,
         Publicacion body) {
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

    @Transactional
    public ResponseEntity<Map<String,Object>> ingresar(UUID id,
                                                         IngresoStock data,
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
}
