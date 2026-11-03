package pe.jmaromas.api.checkout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notifica por Gmail SMTP a la administradora cuando un comprador adjunta
 * evidencia de pago. La notificación queda pendiente si SMTP no está configurado.
 */
@Component
public class CorreoPagoJob {
    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;
    private final String smtpUsuario;
    private final String smtpPassword;
    private final String adminEmail;
    private final String fromAddress;
    private final String frontUrl;

    public CorreoPagoJob(
        JdbcTemplate jdbc,
        JavaMailSender mailSender,
        @Value("${spring.mail.username:}") String smtpUsuario,
        @Value("${spring.mail.password:}") String smtpPassword,
        @Value("${app.email.admin-address:}") String adminEmail,
        @Value("${app.email.from-address:}") String fromAddress,
        @Value("${app.email.front-url:http://localhost:4200}") String frontUrl
    ) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.smtpUsuario = smtpUsuario;
        this.smtpPassword = smtpPassword;
        this.adminEmail = adminEmail;
        this.fromAddress = fromAddress;
        this.frontUrl = frontUrl;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void enviarPendientes() {
        // Sin credenciales no se descartan los avisos.
        if (smtpUsuario.isBlank() || smtpPassword.isBlank() ||
            adminEmail.isBlank() || fromAddress.isBlank()) return;

        InternetAddress remitente;
        try {
            remitente = new InternetAddress(fromAddress, true);
            // Gmail debe enviar desde la misma cuenta que autentica el SMTP.
            if (!smtpUsuario.equalsIgnoreCase(remitente.getAddress())) return;
        } catch (Exception e) {
            return;
        }

        jdbc.update("""
          update app_private.notificaciones_pago
          set estado='ERROR',tomado_en=null,ultimo_error='Reintento tras interrupción'
          where estado='EN_PROCESO' and tomado_en < now()-interval '10 minutes'
          """);
        List<Map<String, Object>> trabajos = jdbc.queryForList("""
          update app_private.notificaciones_pago n
          set estado='EN_PROCESO',tomado_en=now(),intentos=n.intentos+1
          where n.id in (
            select id from app_private.notificaciones_pago
            where estado in ('PENDIENTE','ERROR') and intentos < 5
            order by creado_en limit 10 for update skip locked
          )
          returning n.id,n.pedido_id
          """);

        for (var trabajo : trabajos) {
            long id = ((Number) trabajo.get("id")).longValue();
            UUID pedido = (UUID) trabajo.get("pedido_id");
            try {
                Map<String, Object> p = jdbc.queryForMap("""
                    select codigo,total,metodo_pago from public.pedidos where id=?
                    """, pedido);
                String codigo = p.get("codigo").toString();
                String texto = "Nuevo comprobante de pago pendiente de revisar en JM-AROMAS.\n" +
                    "Pedido: " + codigo + "\n" +
                    "Monto de productos: S/ " + p.get("total") + "\n" +
                    "Medio: " + p.get("metodo_pago") + "\n" +
                    "Revisa el pago directamente en tu cuenta bancaria o Yape. " +
                    "La captura enviada por el cliente NO confirma el abono.\n" +
                    "Abrir panel: " + frontUrl.replaceAll("/+$", "") + "/admin\n";

                MimeMessage mensaje = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(
                    mensaje, false, StandardCharsets.UTF_8.name());
                helper.setFrom(remitente);
                helper.setTo(adminEmail);
                helper.setSubject("JM-AROMAS | Revisar comprobante " + codigo);
                helper.setText(texto, false);
                mailSender.send(mensaje);

                jdbc.update("""
                  update app_private.notificaciones_pago
                  set estado='ENVIADO',enviado_en=now(),ultimo_error=null,tomado_en=null
                  where id=?
                  """, id);
            } catch (Exception e) {
                // Evitar exponer credenciales SMTP en los registros.
                jdbc.update("""
                  update app_private.notificaciones_pago
                  set estado='ERROR',ultimo_error='Error de envío SMTP Gmail',tomado_en=null
                  where id=?
                  """, id);
            }
        }
    }
}
