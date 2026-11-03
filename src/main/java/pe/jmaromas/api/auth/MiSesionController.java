package pe.jmaromas.api.auth;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Identidad y rol real del usuario, independientemente de lo que el navegador afirme.
 * El único rol elevado procede de app_private.administradores.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class MiSesionController {
    private final JdbcTemplate jdbc;
    private final RestClient supabase;
    private final String publishableKey;

    public MiSesionController(
        JdbcTemplate jdbc,
        @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String url,
        @Value("${app.supabase.publishable-key:}") String publishableKey
    ) {
        this.jdbc = jdbc;
        this.publishableKey = publishableKey;
        var http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(4000);
        http.setReadTimeout(6000);
        this.supabase = RestClient.builder().requestFactory(http).baseUrl(url).build();
    }

    public record Identidad(UUID usuarioId, String correo, String rol) {}

    @GetMapping("/me")
    public Identidad consultar(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (publishableKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La autenticación de Supabase no está configurada");
        }
        if (authorization == null || !authorization.startsWith("Bearer ") ||
            authorization.length() > 8192 || authorization.substring(7).isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión con Google");
        }
        String bearer = authorization.substring(7).trim();
        Map<?, ?> user;
        try {
            user = supabase.get()
                .uri("/auth/v1/user")
                .header("apikey", publishableKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Sesión de Google inválida o expirada");
                })
                .body(Map.class);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No se pudo consultar Supabase Auth");
        }
        if (user == null || !(user.get("id") instanceof String id)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        }
        UUID usuarioId;
        try {
            usuarioId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identificador inválido");
        }
        Boolean esAdmin = jdbc.queryForObject("""
            select exists(select 1 from app_private.administradores where usuario_id = ?)
            """, Boolean.class, usuarioId);
        String correo = user.get("email") instanceof String email ? email : "";
        return new Identidad(usuarioId, correo, Boolean.TRUE.equals(esAdmin) ? "ADMIN" : "CLIENTE");
    }
}
