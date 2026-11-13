package pe.jmaromas.api.catalogo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;






@Repository
public class CatalogoRepository {
    private static final String CONSULTA = """
        select p.id, p.nombre, p.slug, p.marca, p.descripcion,
               p.precio-coalesce(d.rebaja,0) as precio,
               case when coalesce(d.rebaja,0)>0 then p.precio else null end as precio_anterior,
               p.contenido_ml, p.contenido_g, p.publico_objetivo, p.familia_olfativa,
               p.stock > 0 as disponible, c.nombre as categoria,
               (select array_agg(i.ruta_storage order by i.orden,i.creada_en,i.id)
                from public.imagenes_producto i where i.producto_id=p.id) as fotos
        from public.productos p
        left join public.categorias c on c.id = p.categoria_id and c.activa = true
        left join lateral (
          select case when t.tipo='PORCENTAJE'
                      then round(p.precio*t.valor/100,2)
                      else t.valor end as rebaja
          from public.descuentos t
          where t.producto_id=p.id and t.activo=true
            and now()>=t.inicio and now()<t.fin
          order by t.inicio desc,t.id desc limit 1
        ) d on true
        where p.activo = true and p.publicado = true
        """;

    private final JdbcTemplate jdbc;
    private final String urlFotos;

    public CatalogoRepository(JdbcTemplate jdbc,
            @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String supabaseUrl) {
        this.jdbc = jdbc;
        this.urlFotos = supabaseUrl.replaceAll("/+$", "")+
            "/storage/v1/object/public/jm-aromas-productos/";
    }

    private final RowMapper<ProductoPublico> mapeo = (rs, rowNum) -> mapear(rs);

    public List<ProductoPublico> listar(
            int pagina,
            int tamano) {
        int paginaSegura = Math.max(0, Math.min(1000, pagina));
        int tamanoSeguro = Math.max(1, Math.min(48, tamano));
        return jdbc.query(CONSULTA + " order by p.creado_en desc limit ? offset ?",
                mapeo, tamanoSeguro, paginaSegura * tamanoSeguro);
    }

    public java.util.Optional<ProductoPublico> detalle(String slug) {
        List<ProductoPublico> resultados = jdbc.query(
                CONSULTA + " and p.slug = ? limit 1", mapeo, slug);
        return resultados.stream().findFirst();
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
                fotos,
                rs.getBigDecimal("precio_anterior"),
                rs.getString("publico_objetivo"),
                rs.getObject("contenido_ml",Integer.class),
                rs.getObject("contenido_g",Integer.class),
                rs.getString("familia_olfativa")
        );
    }
}
