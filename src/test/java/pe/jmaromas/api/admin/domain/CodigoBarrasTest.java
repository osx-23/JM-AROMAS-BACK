package pe.jmaromas.api.admin.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodigoBarrasTest {

    @Test
    void normalizaCodigoInterno() {
        assertEquals("JMA-001",CodigoBarras.normalizar("  jma-001  "));
    }

    @Test
    void aceptaEan13ConChecksumValido() {
        assertEquals("7501055300013",CodigoBarras.normalizar("7501055300013"));
    }

    @Test
    void convierteUpcAlEan13Equivalente() {
        assertEquals("0036000291452",CodigoBarras.normalizar("036000291452"));
    }

    @Test
    void rechazaCodigoConChecksumInvalido() {
        assertThrows(IllegalArgumentException.class,
            ()->CodigoBarras.normalizar("7501055300014"));
    }

    @Test
    void rechazaCodigosNulosYSimbolosNoPermitidos() {
        assertThrows(IllegalArgumentException.class,()->CodigoBarras.normalizar(null));
        assertThrows(IllegalArgumentException.class,()->CodigoBarras.normalizar("A/B C"));
    }
}
