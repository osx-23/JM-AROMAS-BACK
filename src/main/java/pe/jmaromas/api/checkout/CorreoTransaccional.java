package pe.jmaromas.api.checkout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Entrega correo por HTTPS mediante Resend en Railway Free/Hobby.
 * Gmail SMTP queda como alternativa cuando no hay clave de Resend,
 * en entornos que realmente permiten puertos SMTP.
 *
 * Antes de habilitar los avisos por Resend se requiere un dominio de
 * envío verificado y la variable RESEND_FROM_ADDRESS. Nunca usar
 * la clave privada en el frontend ni incluirla en errores de log.
 */
@Component
public class CorreoTransaccional {
    private final RestClient resend = RestClient.builder()
        .baseUrl("https://api.resend.com").build();
    private final JavaMailSender smtp;
    private final String resendKey;
    private final String resendFrom;
    private final String replyTo;
    private final String smtpUsuario;
    private final String smtpPassword;
    private final String smtpFrom;

    public CorreoTransaccional(
        JavaMailSender smtp,
        @Value("${RESEND_API_KEY:}") String resendKey,
        @Value("${RESEND_FROM_ADDRESS:}") String resendFrom,
        @Value("${ADMIN_NOTIFICATION_EMAIL:}") String replyTo,
        @Value("${spring.mail.username:}") String smtpUsuario,
        @Value("${spring.mail.password:}") String smtpPassword,
        @Value("${app.email.from-address:}") String smtpFrom
    ) {
        this.smtp = smtp;
        this.resendKey = resendKey;
        this.resendFrom = resendFrom;
        this.replyTo = replyTo;
        this.smtpUsuario = smtpUsuario;
        this.smtpPassword = smtpPassword;
        this.smtpFrom = smtpFrom;
    }

    public boolean configurado() {
        // Si existe clave Resend, nunca intentar Gmail SMTP por accidente:
        // esperar a verificar dominio y proporcionar el remitente.
        if (!resendKey.isBlank()) return !resendFrom.isBlank();
        return !smtpUsuario.isBlank() && !smtpPassword.isBlank()
            && !smtpFrom.isBlank();
    }

    public void enviar(String destinatario,String asunto,String texto,
                       String idempotencia) throws Exception {
        if (!configurado()) throw new IllegalStateException("Correo no configurado");
        if (!resendKey.isBlank()) {
            // La respuesta 2xx confirma recepción por Resend, no entrega final.
            Map<String,Object> payload = new java.util.LinkedHashMap<>();
            payload.put("from",resendFrom);
            payload.put("to",List.of(destinatario));
            payload.put("subject",asunto);
            payload.put("text",texto);
            if (!replyTo.isBlank()) payload.put("reply_to",replyTo);
            resend.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+resendKey)
                .header("Idempotency-Key",idempotencia)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
            return;
        }

        InternetAddress remitente = new InternetAddress(smtpFrom,true);
        if (!smtpUsuario.equalsIgnoreCase(remitente.getAddress()))
            throw new IllegalStateException("Remitente SMTP incompatible");
        MimeMessage mensaje=smtp.createMimeMessage();
        MimeMessageHelper helper=new MimeMessageHelper(
            mensaje,false,StandardCharsets.UTF_8.name());
        helper.setFrom(remitente);
        helper.setTo(new InternetAddress(destinatario,true));
        helper.setSubject(asunto);
        helper.setText(texto,false);
        smtp.send(mensaje);
    }
}
