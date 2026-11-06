package pe.jmaromas.api.checkout;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntregaServiceTest {
    private final EntregaService entregas = new EntregaService();

    private EntregaService.Zona zonaPorPrecio(int precio) {
        return entregas.zonasLima().stream()
            .filter(x -> x.precio()==precio).findFirst().orElseThrow();
    }

    @Test
    void deliveryLimaAplicaPreciosDeTarifario() {
        for(int precio: new int[]{10,16,20}) {
            var zona=zonaPorPrecio(precio);
            var quote=entregas.cotizar("DELIVERY_LIMA",zona.id(),null,
                2,zona.departamento(),zona.distrito());
            assertEquals(BigDecimal.valueOf(precio),quote.costo());
            assertEquals("DELIVERY_LIMA",quote.tipo());
        }
    }

    @Test
    void shalomSumaDosSolesPorCadaPerfumeExtra() {
        var agencia=entregas.agenciasShalom().getFirst();
        assertEquals(BigDecimal.valueOf(8),
            entregas.cotizar("SHALOM",null,agencia.id(),1,"Cusco","Cusco").costo());
        assertEquals(BigDecimal.valueOf(12),
            entregas.cotizar("SHALOM",null,agencia.id(),3,"Cusco","Cusco").costo());
        assertEquals(BigDecimal.valueOf(16),
            entregas.cotizar("SHALOM",null,agencia.id(),5,"Cusco","Cusco").costo());
    }

    @Test
    void directorioShalomIncluyeSedesDeReferencia() {
        assertTrue(entregas.agenciasShalom().size()>300);
        assertTrue(entregas.agenciasShalom().stream()
            .anyMatch(a -> a.nombre().equals("ABANCAY")));
        assertTrue(entregas.agenciasShalom().stream()
            .anyMatch(a -> a.nombre().equals("JESUS MARIA")));
    }

    @Test
    void noConfiaEnZonasFraudulentasNiAgenciasArbitrarias() {
        var zona=zonaPorPrecio(10);
        assertThrows(IllegalArgumentException.class,
            () -> entregas.cotizar("DELIVERY_LIMA","NO_EXISTE",null,
                1,"Lima","Miraflores"));
        assertThrows(IllegalArgumentException.class,
            () -> entregas.cotizar("DELIVERY_LIMA",zona.id(),null,
                1,"Cusco",zona.distrito()));
        assertThrows(IllegalArgumentException.class,
            () -> entregas.cotizar("SHALOM",null,"Sucursal inventada",
                1,"Lima","Lima"));
        assertThrows(IllegalArgumentException.class,
            () -> entregas.cotizar("GRATIS",zona.id(),null,1,"Lima","Miraflores"));
    }

    @Test
    void noAceptaCantidadCero() {
        var agencia=entregas.agenciasShalom().getFirst();
        assertThrows(IllegalArgumentException.class,
            () -> entregas.cotizar("SHALOM",null,agencia.id(),0,"Lima","Lima"));
    }
}
