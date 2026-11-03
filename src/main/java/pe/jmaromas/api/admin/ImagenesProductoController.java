package pe.jmaromas.api.admin;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fotos de perfumes: escrituras limitadas a ADMIN por AdminAuthFilter.
 * Solo se usan credenciales privilegiadas en el backend (no en Angular).
 */
@RestController
@RequestMapping("/api/v1/admin/productos/{productoId}/imagenes")
public class ImagenesProductoController {
    private static final String BUCKET = "jm-aromas-productos";
    private static final int MAX_FOTOS = 5;
    private static final long MAX_ARCHIVO = 5L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final RestClient storage;
    private final String serviceRoleKey;
    private final String urlPublica;

    public ImagenesProductoController(JdbcTemplate jdbc,
        @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String supabaseUrl,
        @Value("${app.supabase.service-role-key:}") String serviceRoleKey) {
        this.jdbc = jdbc;
        this.serviceRoleKey = serviceRoleKey;
        String base = supabaseUrl.replaceAll("/+$", "");
        this.urlPublica = base + "/storage/v1/object/public/" + BUCKET + "/";
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(4000);
        requestFactory.setReadTimeout(12000);
        this.storage = RestClient.builder().requestFactory(requestFactory).baseUrl(base).build();
    }

    public record Imagen(UUID id, String url, String descripcionAlt, int orden) {}

    @GetMapping
    public List<Imagen> listar(@PathVariable UUID productoId) {
        verificarProducto(productoId, false);
        return lista(productoId);
    }

    private List<Imagen> lista(UUID productoId) {
        return jdbc.query("""
            select id, ruta_storage, descripcion_alt, orden from public.imagenes_producto
            where producto_id=? order by orden,creada_en,id
            """, (rs, n) -> new Imagen(
                rs.getObject("id", UUID.class),
                urlPublica + rs.getString("ruta_storage"),
                rs.getString("descripcion_alt"),
                rs.getInt("orden")
            ), productoId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Imagen> subir(@PathVariable UUID productoId,
        @RequestParam("archivo") MultipartFile archivo) throws IOException {
        verificarStorage();
        // Bloquear el producto para serializar subidas simultáneas.
        String nombre = verificarProducto(productoId, true);
        int count = jdbc.queryForObject("""
            select count(*) from public.imagenes_producto where producto_id=?
            """, Integer.class, productoId);
        if (count >= MAX_FOTOS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Máximo 5 fotos por producto. Elimina una para agregar otra.");
        if (archivo.isEmpty() || archivo.getSize() > MAX_ARCHIVO)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La imagen debe tener un máximo de 5 MB.");

        byte[] bytes = archivo.getBytes();
        TipoImagen tipo = inspeccionar(bytes);
        String ruta = productoId + "/" + UUID.randomUUID() + tipo.extension();
        try {
            storage.post().uri("/storage/v1/object/" + BUCKET + "/" + ruta)
                .header("apikey", serviceRoleKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey)
                .contentType(MediaType.parseMediaType(tipo.mime()))
                .body(bytes).retrieve().toBodilessEntity();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo guardar la foto en Supabase Storage");
        }

        UUID id = UUID.randomUUID();
        try {
            int siguiente = jdbc.queryForObject("""
                select coalesce(max(orden),-1)+1 from public.imagenes_producto
                where producto_id=?
                """, Integer.class, productoId);
            jdbc.update("""
                insert into public.imagenes_producto
                (id,producto_id,ruta_storage,descripcion_alt,orden) values(?,?,?,?,?)
                """, id, productoId, ruta, "Foto de " + nombre, siguiente);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Imagen(id, urlPublica+ruta, "Foto de "+nombre, siguiente));
        } catch (Exception e) {
            // Evitar dejar una foto huérfana cuando falle el registro en PostgreSQL.
            try {
                storage.delete().uri("/storage/v1/object/"+BUCKET+"/"+ruta)
                    .header("apikey", serviceRoleKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer "+serviceRoleKey)
                    .retrieve().toBodilessEntity();
            } catch (Exception ignored) { /* Se podrá limpiar en mantenimiento. */ }
            throw e;
        }
    }

    @PatchMapping("/{imagenId}/principal")
    @Transactional
    public List<Imagen> principal(@PathVariable UUID productoId, @PathVariable UUID imagenId) {
        verificarProducto(productoId, true);
        List<Integer> ordenes = jdbc.query("""
            select orden from public.imagenes_producto
            where producto_id=? and id=? for update
            """, (rs, n) -> rs.getInt(1), productoId, imagenId);
        if (ordenes.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La foto no existe");
        jdbc.update("""
            update public.imagenes_producto set orden=orden+1
            where producto_id=? and id<>?
            """, productoId, imagenId);
        jdbc.update("update public.imagenes_producto set orden=0 where id=?", imagenId);
        return lista(productoId);
    }

    @DeleteMapping("/{imagenId}")
    @Transactional
    public ResponseEntity<Void> eliminar(@PathVariable UUID productoId, @PathVariable UUID imagenId) {
        verificarStorage();
        verificarProducto(productoId, true);
        List<String> rutas = jdbc.query("""
            select ruta_storage from public.imagenes_producto
            where producto_id=? and id=? for update
            """, (rs, n) -> rs.getString(1), productoId, imagenId);
        if (rutas.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La foto no existe");
        // Eliminar del bucket primero: si falla, no eliminar el registro en PostgreSQL.
        try {
            storage.delete().uri("/storage/v1/object/" + BUCKET + "/" + rutas.getFirst())
                .header("apikey", serviceRoleKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey)
                .retrieve().toBodilessEntity();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo eliminar la foto de Supabase Storage");
        }
        jdbc.update("""
            delete from public.imagenes_producto where producto_id=? and id=?
            """, productoId, imagenId);
        return ResponseEntity.noContent().build();
    }

    private String verificarProducto(UUID productoId, boolean bloquear) {
        String sql = "select nombre from public.productos where id=?"
            + (bloquear ? " for update" : "");
        List<String> nombres = jdbc.query(sql, (rs, n) -> rs.getString(1), productoId);
        if (nombres.isEmpty()) throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "El producto no existe");
        return nombres.getFirst();
    }

    private void verificarStorage() {
        if (serviceRoleKey.isBlank()) throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SUPABASE_SERVICE_ROLE_KEY no configurada en el backend");
    }

    private record TipoImagen(String extension, String mime) {}
    private static TipoImagen inspeccionar(byte[] b) {
        if (b.length >= 8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71 &&
            b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10)
            return new TipoImagen(".png", "image/png");
        if (b.length >= 3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255)
            return new TipoImagen(".jpg", "image/jpeg");
        if (b.length >= 12 && b[0]=='R' && b[1]=='I' && b[2]=='F' &&
            b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P')
            return new TipoImagen(".webp", "image/webp");
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Formato no admitido. Selecciona una foto JPG, PNG o WEBP.");
    }
}
