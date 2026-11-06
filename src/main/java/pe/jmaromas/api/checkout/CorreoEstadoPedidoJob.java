package pe.jmaromas.api.checkout;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bandeja transaccional para compradores: todo cambio de estado de pedido o
 * pago en public.pedidos encola un aviso mediante trigger PostgreSQL.
 * Envío asíncrono y reintentable, sin bloquear el checkout ni los paneles.
 */
@Component
public class CorreoEstadoPedidoJob {
    private final JdbcTemplate jdbc;
    private final CorreoTransaccional correo;
    private final String frontUrl;

    public CorreoEstadoPedidoJob(
        JdbcTemplate jdbc,
        CorreoTransaccional correo,
        @Value("${app.email.front-url:https://jmaromas.shop}") String frontUrl
    ) {
        this.jdbc = jdbc;
        this.correo = correo;
        this.frontUrl = frontUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    public void enviarPendientes() {
        // En Railway Hobby usar Resend HTTPS; sin remitente verificado no consumir intentos.
        if (!correo.configurado()) return;

        // Recupera trabajos abandonados si el proceso se reinició a mitad del envío.
        jdbc.update("""
            update app_private.notificaciones_estado_pedido
               set estado_envio='ERROR',tomado_en=null,ultimo_error='Envío interrumpido'
             where estado_envio='EN_PROCESO' and tomado_en < now()-interval '10 minutes'
            """);

        List<Map<String, Object>> trabajos = jdbc.queryForList("""
            update app_private.notificaciones_estado_pedido n
               set estado_envio='EN_PROCESO',tomado_en=now(),intentos=n.intentos+1
             where n.id in (
                select id from app_private.notificaciones_estado_pedido
                where estado_envio in ('PENDIENTE','ERROR') and intentos < 5
                order by creado_en,id limit 10 for update skip locked
             )
            returning n.id,n.destinatario,n.codigo,n.estado_pedido,n.estado_pago,n.evento
            """);

        for (Map<String, Object> trabajo : trabajos) {
            long id = ((Number) trabajo.get("id")).longValue();
            try {
                String destinatario = (String) trabajo.get("destinatario");
                String codigo = (String) trabajo.get("codigo");
                String estado = (String) trabajo.get("estado_pedido");
                String pago = (String) trabajo.get("estado_pago");
                String evento = (String) trabajo.get("evento");
                String asunto = "JM Aromas | " + codigo + " — " + etiquetaEstado(estado, pago, evento);

                String texto = "Hola,\n\n"
                    + (evento.equals("CREADO")
                        ? "Hemos registrado tu solicitud de pedido en JM Aromas.\n"
                        : "Hay una actualización de tu pedido en JM Aromas.\n")
                    + "\nPedido: " + codigo
                    + "\nEstado del pedido: " + nombrePedido(estado)
                    + "\nEstado del pago: " + nombrePago(pago)
                    + "\n\n" + orientacion(estado, pago)
                    + "\n\nSi compraste con Google, consulta tus pedidos en: "
                    + frontUrl + "/mi-perfil"
                    + "\nSi compraste como invitado, utiliza el enlace privado de seguimiento"
                    + " que guardaste al crear el pedido. No compartas ese enlace."
                    + "\n\nConsultas: responde a este correo o contacta a la tienda."
                    + "\nJM Aromas — " + frontUrl;

                correo.enviar(destinatario, asunto, texto, "jm-estado/" + id);

                jdbc.update("""
                    update app_private.notificaciones_estado_pedido
                    set estado_envio='ENVIADO',enviado_en=now(),
                        tomado_en=null,ultimo_error=null
                    where id=?
                    """,id);
            } catch (Exception e) {
                // Nunca registrar PII o contraseñas SMTP en los errores.
                jdbc.update("""
                    update app_private.notificaciones_estado_pedido
                    set estado_envio='ERROR',tomado_en=null,
                        ultimo_error='Fallo al enviar correo transaccional'
                    where id=?
                    """,id);
            }
        }
    }

    private static String etiquetaEstado(String estado,String pago,String evento) {
        if ("ESTADO_PAGO".equals(evento)) return "Actualización de pago";
        if ("AMBOS".equals(evento)) return "Pedido y pago actualizados";
        if ("CREADO".equals(evento)) return "Pedido registrado";
        return nombrePedido(estado);
    }

    private static String nombrePedido(String estado) {
        return switch(estado.toUpperCase(Locale.ROOT)) {
            case "PENDIENTE" -> "Pendiente";
            case "CONTACTADO" -> "En coordinación";
            case "CONFIRMADO" -> "Confirmado";
            case "PREPARANDO" -> "Pedido en preparación";
            case "EN_CAMINO" -> "Pedido en camino";
            case "FINALIZADO", "ENTREGADO" -> "Pedido finalizado";
            case "CANCELADO" -> "Cancelado";
            default -> "En revisión";
        };
    }

    private static String nombrePago(String pago) {
        return switch(pago.toUpperCase(Locale.ROOT)) {
            case "PENDIENTE" -> "Pendiente";
            case "PENDIENTE_CONFIRMACION" -> "Comprobante recibido, en revisión";
            case "PAGADO" -> "Pago confirmado";
            case "RECHAZADO" -> "Comprobante rechazado";
            case "ANULADO" -> "Anulado";
            default -> "En revisión";
        };
    }

    private static String orientacion(String estado,String pago) {
        if ("CANCELADO".equals(estado)) {
            return "Este pedido fue cancelado o venció su reserva. Si realizaste un pago, contacta a la tienda.";
        }
        if ("RECHAZADO".equals(pago)) {
            return "No se pudo aprobar el comprobante. Revisa tu pedido y contacta a la tienda; "
                + "si la reserva sigue vigente, puedes presentar un nuevo comprobante.";
        }
        if ("PENDIENTE_CONFIRMACION".equals(pago)) {
            return "Recibimos tu comprobante. El equipo comprobará el abono antes de confirmarlo.";
        }
        if ("FINALIZADO".equals(estado) || "ENTREGADO".equals(estado))
            return "Tu pedido ha finalizado. Gracias por elegirnos.";
        if ("EN_CAMINO".equals(estado))
            return "Tu pedido ya está en camino o fue despachado a la agencia elegida. Revisa el seguimiento de tu pedido.";
        if ("PREPARANDO".equals(estado))
            return "Tu pedido está en preparación. Te avisaremos cuando cambie su estado.";
        if ("PAGADO".equals(pago)) return "El pago fue verificado. La tienda continuará gestionando tu pedido.";
        return "Puedes revisar el seguimiento de tu pedido y consultar cualquier duda con la tienda.";
    }
}
