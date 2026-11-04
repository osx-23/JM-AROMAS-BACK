package pe.jmaromas.api.checkout;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Comportamiento de flags: WhatsApp y cobros web no comparten habilitación. */
class CheckoutPublicControllerTest {
    private CheckoutPublicController controlador(JdbcTemplate jdbc,
                                                   boolean web,boolean whatsapp) {
        CheckoutPublicController checkout=new CheckoutPublicController(
            jdbc,"https://example.supabase.co","public_test_key");
        ReflectionTestUtils.setField(checkout,"checkoutEnabled",web);
        ReflectionTestUtils.setField(checkout,"whatsappEnabled",whatsapp);
        return checkout;
    }

    @Test
    void bloqueoDeCobrosWebNoDependeDeWhatsApp() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var c=controlador(jdbc,false,true);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"YAPE",null,null,List.of(),false);
        var error=assertThrows(ResponseStatusException.class,
            () -> c.crear(solicitud,null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void sePuedeEntrarAlFlujoDeWhatsAppSinHabilitarPagosWeb() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(UUID.class)))
            .thenReturn(Boolean.FALSE);
        var c=controlador(jdbc,false,true);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"COORDINAR_WHATSAPP",null,null,List.of(),false);
        // El flag dejó pasar la solicitud: se evalúa la validación del cliente.
        assertThrows(IllegalArgumentException.class,() -> c.crear(solicitud,null));
    }

    @Test
    void whatsappTambienTieneUnInterruptorDeDesactivacion() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var c=controlador(jdbc,false,false);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"COORDINAR_WHATSAPP",null,null,List.of(),false);
        var error=assertThrows(ResponseStatusException.class,
            () -> c.crear(solicitud,null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
