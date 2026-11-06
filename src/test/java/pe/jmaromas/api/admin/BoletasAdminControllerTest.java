package pe.jmaromas.api.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
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

/** No generar CPE: solo vincular referencias de boletas previamente emitidas en SEE-SOL. */
class BoletasAdminControllerTest {
    private static final String RUC_FICTICIO = "20123456789";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final BoletasAdminController controller = new BoletasAdminController(jdbc, RUC_FICTICIO);

    private BoletasAdminController.RegistrarBoleta solicitud(String serie,int numero,boolean confirmado) {
        return new BoletasAdminController.RegistrarBoleta(
            UUID.randomUUID(), serie, numero, LocalDate.now(), confirmado);
    }

    @Test
    void noSeRegistraNingunaBoletaSinDeclaracionExplicitaDeEmisionSunat() {
        var error=assertThrows(IllegalArgumentException.class,
            () -> controller.registrar(solicitud("EB01",3,false),request));
        assertTrue(error.getMessage().contains("ya fue emitida"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void sinRucConfiguradoSeBloqueaElRegistro() {
        var sinRuc=new BoletasAdminController(jdbc,"");
        var error=assertThrows(ResponseStatusException.class,
            () -> sinRuc.registrar(solicitud("EB01",3,true),request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void noPermiteSerieInvalidaNiFechaFutura() {
        assertThrows(IllegalArgumentException.class,
            () -> controller.registrar(solicitud("XXX",3,true),request));
        var f=new BoletasAdminController.RegistrarBoleta(
            UUID.randomUUID(),"EB01",3,LocalDate.now().plusDays(2),true);
        assertThrows(IllegalArgumentException.class,() -> controller.registrar(f,request));
        verifyNoInteractions(jdbc);
    }

    @Test
    void exigePagoConfirmadoAntesDeRegistrarElComprobante() {
        UUID administrador=UUID.randomUUID();
        when(request.getAttribute("adminUserId")).thenReturn(administrador);
        when(jdbc.queryForList(anyString(),any(UUID.class))).thenReturn(List.of(
            Map.of("codigo","JMA-TEST","estado","PENDIENTE",
                "estado_pago","PENDIENTE","total",new BigDecimal("42.50"))));
        var error=assertThrows(ResponseStatusException.class,
            () -> controller.registrar(solicitud("EB01",3,true),request));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        assertTrue(error.getReason().contains("pago confirmado"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void requiereAdministradoraIdentificadaPorFiltroDelBackend() {
        var error=assertThrows(ResponseStatusException.class,
            () -> controller.registrar(solicitud("EB01",4,true),request));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void listadosSonSoloDeLaApiAdministrativa() {
        controller.listar();
        controller.pedidosElegibles();
        verify(jdbc,times(2)).queryForList(anyString());
    }
}
