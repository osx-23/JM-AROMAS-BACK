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
                           BigDecimal precio, Integer stock) {}
    public record IngresoStock(Integer cantidad, String motivo) {}

    @GetMapping
    public List<Map<String, Object>> listar() {
        return jdbc.queryForList("""
            select id, codigo_barras as "codigoBarras", sku, nombre, marca, descripcion,
                   categoria_id as "categoriaId", precio, stock, activo, publicado
            from public.productos order by creado_en desc limit 100
            """);
    }

    @GetMapping("/codigo-barras/{codigo}")
    public ResponseEntity<Map<String,Object>> buscar(@PathVariable String codigo) {
        String canonico = codigoNormalizado(codigo);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id, codigo_barras as "codigoBarras", sku, nombre, marca, descripcion,
                   categoria_id as "categoriaId", precio, stock, activo, publicado
            from public.productos where codigo_barras = ? limit 1
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
        UUID id = UUID.randomUUID();
        String slug = "producto-" + id.toString();
        try {
            int rows = jdbc.update("""
                insert into public.productos
                    (id, codigo_barras, sku, slug, nombre, marca, descripcion, categoria_id,
                     precio, stock, activo, publicado)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, false)
                """, id, codigo, sku, slug, data.nombre().strip(),
                blankToNull(data.marca()), blankToNull(data.descripcion()),
                data.categoriaId(), data.precio(), data.stock());
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
