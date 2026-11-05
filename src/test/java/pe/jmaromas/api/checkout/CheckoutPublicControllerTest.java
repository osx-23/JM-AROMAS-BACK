package pe.jmaromas.api.checkout;

import java.util.List;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
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
        return controlador(jdbc,web,whatsapp,false);
    }

    private CheckoutPublicController controlador(JdbcTemplate jdbc,
        boolean legacy,boolean whatsapp,boolean manual) {
        CheckoutPublicController checkout=new CheckoutPublicController(
            jdbc,"https://example.supabase.co","public_test_key");
        ReflectionTestUtils.setField(checkout,"checkoutEnabled",legacy);
        ReflectionTestUtils.setField(checkout,"whatsappEnabled",whatsapp);
        ReflectionTestUtils.setField(checkout,"manualPaymentsEnabled",manual);
        return checkout;
    }

    @Test
    void bloqueoDeCobrosWebNoDependeDeWhatsApp() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var c=controlador(jdbc,false,true);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"YAPE",null,null,List.of(),false);
        var error=assertThrows(ResponseStatusException.class,
            () -> c.crear(solicitud,null,null));
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
        assertThrows(IllegalArgumentException.class,() -> c.crear(solicitud,null,null));
    }

    @Test
    void pagosManualesFuncionaConInterruptorLegadoDesactivado() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(UUID.class)))
            .thenReturn(Boolean.FALSE);
        var checkout=controlador(jdbc,false,true,true);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"YAPE",null,null,List.of(),false);
        // No se rechaza por el flag: llega hasta las validaciones del cliente.
        var error=assertThrows(IllegalArgumentException.class,
            () -> checkout.crear(solicitud,null,null));
        assertTrue(error.getMessage().contains("contacto"));
    }

    @Test
    void pagosManualesDesactivadosSiguenBloqueados() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var checkout=controlador(jdbc,false,true,false);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"TRANSFERENCIA",null,null,List.of(),false);
        var error=assertThrows(ResponseStatusException.class,
            () -> checkout.crear(solicitud,null,null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void whatsappTambienTieneUnInterruptorDeDesactivacion() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var c=controlador(jdbc,false,false);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"COORDINAR_WHATSAPP",null,null,List.of(),false);
        var error=assertThrows(ResponseStatusException.class,
            () -> c.crear(solicitud,null,null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void informaLaDireccionInvalidaAntesDeReservarStock() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(UUID.class)))
            .thenReturn(Boolean.FALSE);
        var checkout=controlador(jdbc,false,true);
        var cliente=new CheckoutPublicController.Cliente(
            "Comprador Prueba","12345678","987654321","comprador@ejemplo.com",
            "Lima","Lima","Miraflores","Av","sin referencia");
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"COORDINAR_WHATSAPP",null,cliente,List.of(),false);
        var error=assertThrows(IllegalArgumentException.class,
            () -> checkout.crear(solicitud,null,null));
        assertTrue(error.getMessage().contains("Dirección"));
    }

    @Test
    void informaLaProvinciaInvalidaAntesDeReservarStock() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(UUID.class)))
            .thenReturn(Boolean.FALSE);
        var checkout=controlador(jdbc,false,true);
        var cliente=new CheckoutPublicController.Cliente(
            "Comprador Prueba","12345678","987654321","comprador@ejemplo.com",
            "Lima","L","Miraflores","Avenida Principal 100",null);
        var solicitud=new CheckoutPublicController.PedidoSolicitud(
            UUID.randomUUID(),"COORDINAR_WHATSAPP",null,cliente,List.of(),false);
        var error=assertThrows(IllegalArgumentException.class,
            () -> checkout.crear(solicitud,null,null));
        assertTrue(error.getMessage().contains("Provincia"));
    }

    private CheckoutPublicController.Cliente clienteValido() {
        return new CheckoutPublicController.Cliente(
            "Cliente de Prueba","12345678","987654321","cliente@ejemplo.com",
            "Lima","Lima","Lince","Jirón principal 123",null);
    }

    @Test
    void permiteCuatroPedidosEnUnaHoraYDiecinueveEnElDia() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),eq("987654321")))
            .thenReturn(Map.of("hora",4L,"dia",19L));
        assertDoesNotThrow(() -> controlador(jdbc,false,true)
            .limitarPedidosPorTelefono(clienteValido()));
    }

    @Test
    void bloqueaDesdeElQuintoPedidoEnLaUltimaHoraYCalculaEspera() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),eq("987654321")))
            .thenReturn(Map.of(
                "hora",5L,"dia",5L,
                "limiteHora",Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)))
            ));
        ResponseStatusException e=assertThrows(ResponseStatusException.class,
            () -> controlador(jdbc,false,true).limitarPedidosPorTelefono(clienteValido()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,e.getStatusCode());
        assertTrue(e.getReason().contains("5 pedidos por hora"));
        assertTrue(e.getReason().contains("30 minutos"));
    }

    @Test
    void bloqueaDesdeElVigesimoPedidoEnVentanaDeVeinticuatroHoras() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),eq("987654321")))
            .thenReturn(Map.of("hora",1L,"dia",20L,
                "limiteDia",Timestamp.from(Instant.now().minus(Duration.ofHours(23)))));
        ResponseStatusException e=assertThrows(ResponseStatusException.class,
            () -> controlador(jdbc,false,true).limitarPedidosPorTelefono(clienteValido()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,e.getStatusCode());
        assertTrue(e.getReason().contains("20 en 24 horas"));
        assertTrue(e.getReason().contains("60 minutos"));
    }

    @Test
    void queryExcluyePedidosCanceladosYReservasExpiradas() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),eq("987654321")))
            .thenReturn(Map.of("hora",0L,"dia",0L));
        controlador(jdbc,false,true).limitarPedidosPorTelefono(clienteValido());
        org.mockito.ArgumentCaptor<String> sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(sql.capture(),eq("987654321"));
        assertTrue(sql.getValue().contains("estado <> 'CANCELADO'"));
        assertTrue(sql.getValue().contains("expira_en <= now()"));
    }

    @Test
    void elCalculoDeEsperaNuncaDevuelveCero() {
        assertEquals(1,CheckoutPublicController.minutosHastaLiberar(
            Timestamp.from(Instant.now().minus(Duration.ofHours(2))),Duration.ofHours(1)));
    }
}
