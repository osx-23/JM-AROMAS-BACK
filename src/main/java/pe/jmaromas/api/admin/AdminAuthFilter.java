package pe.jmaromas.api.admin;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Valida el token contra Supabase Auth Y autoriza en la tabla privada, nunca en user_metadata. */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final String publishableKey;
    private final RestClient authClient;

    public AdminAuthFilter(JdbcTemplate jdbc,
                           @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String supabaseUrl,
                           @Value("${app.supabase.publishable-key:}") String publishableKey) {
        this.jdbc = jdbc;
        this.publishableKey = publishableKey;
        var http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(4000);
        http.setReadTimeout(6000);
        this.authClient = RestClient.builder()
                .requestFactory(http)
                .baseUrl(supabaseUrl)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // CORS preflight: no contiene autenticación; únicamente se permite si CORS ya coincide.
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (publishableKey.isBlank()) {
            res.sendError(503, "Autenticación Supabase sin configurar");
            return;
        }
        if (header == null || !header.startsWith("Bearer ") || header.length() > 8192) {
            res.sendError(401, "Sesión requerida");
            return;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            res.sendError(401, "Sesión requerida");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> user = authClient.get()
                    .uri("/auth/v1/user")
                    .header("apikey", publishableKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new IllegalArgumentException("JWT rechazado por Supabase");
                    })
                    .body(Map.class);
            if (user == null || !(user.get("id") instanceof String id)) {
                res.sendError(401, "Usuario no válido");
                return;
            }
            UUID userId = UUID.fromString(id);
            Boolean admin = jdbc.queryForObject(
                "select exists(select 1 from app_private.administradores where usuario_id = ?)",
                Boolean.class, userId);
            if (!Boolean.TRUE.equals(admin)) {
                res.sendError(403, "Solo administradores");
                return;
            }
            req.setAttribute("adminUserId", userId);
            chain.doFilter(req, res);
        } catch (IllegalArgumentException e) {
            res.sendError(401, "Sesión no válida");
        } catch (Exception e) {
            res.sendError(503, "No se pudo verificar la sesión");
        }
    }
}
