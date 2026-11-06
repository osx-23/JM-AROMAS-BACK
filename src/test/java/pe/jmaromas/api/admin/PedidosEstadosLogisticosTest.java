package pe.jmaromas.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PedidosEstadosLogisticosTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final PedidosWhatsappAdminController controller =
        new PedidosWhatsappAdminController(jdbc);

    private void estadoActual(String estado, String pago) {
        when(request.getAttribute("adminUserId")).thenReturn(UUID.randomUUID());
        when(jdbc.queryForList(anyString(),any(UUID.class)))
            .thenReturn(List.of(Map.of("estado",estado,"estado_pago",pago)));
    }

    private Map<String,Object> avanzar(String nuevo) {
        return controller.actualizar(UUID.randomUUID(),
            new PedidosWhatsappAdminController.EstadoForm(nuevo,"Prueba unitaria"),
            request).getBody();
    }

    @Test
    void puedePasarDeConfirmadoAPreparacion() {
        estadoActual("CONFIRMADO","PAGADO");
        assertEquals("PREPARANDO",avanzar("PREPARANDO").get("estado"));
        verify(jdbc,atLeastOnce()).update(anyString(),any(Object[].class));
    }

    @Test
    void puedePasarDePreparacionAEnCamino() {
        estadoActual("PREPARANDO","PAGADO");
        assertEquals("EN_CAMINO",avanzar("EN_CAMINO").get("estado"));
    }

    @Test
    void puedeFinalizarCuandoElPedidoYaEstaEnCamino() {
        estadoActual("EN_CAMINO","PAGADO");
        assertEquals("FINALIZADO",avanzar("FINALIZADO").get("estado"));
    }

    @Test
    void noPermiteDespacharSinPagoConfirmado() {
        estadoActual("PREPARANDO","PENDIENTE");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> avanzar("EN_CAMINO"));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void noPermiteFinalizarDirectamenteDesdeConfirmado() {
        estadoActual("CONFIRMADO","PAGADO");
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            () -> avanzar("FINALIZADO"));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
    }
}
