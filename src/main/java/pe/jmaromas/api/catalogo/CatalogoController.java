package pe.jmaromas.api.catalogo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/productos")
public class CatalogoController {
    private static final String CONSULTA = """
        select p.id, p.nombre, p.slug, p.marca, p.descripcion, p.precio,
               p.stock > 0 as disponible, c.nombre as categoria,
               (select array_agg(i.ruta_storage order by i.orden,i.creada_en,i.id)
                from public.imagenes_producto i where i.producto_id=p.id) as fotos
        from public.productos p
        left join public.categorias c on c.id = p.categoria_id and c.activa = true
        where p.activo = true and p.publicado = true
        """;

    private final JdbcTemplate jdbc;
    private final String urlFotos;

    public CatalogoController(JdbcTemplate jdbc,
            @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String supabaseUrl) {
        this.jdbc = jdbc;
        this.urlFotos = supabaseUrl.replaceAll("/+$", "")+
            "/storage/v1/object/public/jm-aromas-productos/";
    }

    private final RowMapper<ProductoPublico> mapeo = (rs, rowNum) -> mapear(rs);

    @GetMapping
    public List<ProductoPublico> listar(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "12") int tamano) {
        int paginaSegura = Math.max(0, Math.min(1000, pagina));
        int tamanoSeguro = Math.max(1, Math.min(48, tamano));
        return jdbc.query(CONSULTA + " order by p.creado_en desc limit ? offset ?",
                mapeo, tamanoSeguro, paginaSegura * tamanoSeguro);
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ProductoPublico> detalle(@PathVariable String slug) {
        List<ProductoPublico> resultados = jdbc.query(
                CONSULTA + " and p.slug = ? limit 1", mapeo, slug);
        return resultados.isEmpty()
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(resultados.getFirst());
    }

    private ProductoPublico mapear(ResultSet rs) throws SQLException {
        Array fotosSql = rs.getArray("fotos");
        List<String> fotos = fotosSql == null ? List.of()
            : Arrays.stream((String[]) fotosSql.getArray())
                .map(ruta -> urlFotos + ruta).toList();
        return new ProductoPublico(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("nombre"),
                rs.getString("slug"),
                rs.getString("marca"),
                rs.getString("descripcion"),
                rs.getBigDecimal("precio"),
                rs.getBoolean("disponible"),
                rs.getString("categoria"),
                fotos
        );
    }
}
