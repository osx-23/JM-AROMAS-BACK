package pe.jmaromas.api.facturacion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SunatSoapClientTest {
    @Test
    void extraeTicketDeRespuestaSoapSinAutenticarseNiConectarse() {
        var xml=SunatSoapClient.parseSeguro("""
            <?xml version="1.0"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                xmlns:sunat="http://service.sunat.gob.pe">
              <soap:Body>
                <sunat:sendSummaryResponse><ticket>20260000123</ticket></sunat:sendSummaryResponse>
              </soap:Body>
            </soap:Envelope>
            """);
        assertEquals("20260000123",SunatSoapClient.contenidoPrimerElemento(xml,"ticket"));
    }

    @Test
    void interpretaCodigoDeEstadoSinConfundirloConAceptacion() {
        var xml=SunatSoapClient.parseSeguro("""
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body><getStatusResponse>
                <status><statusCode>98</statusCode></status>
              </getStatusResponse></soap:Body>
            </soap:Envelope>
            """);
        assertEquals("98",SunatSoapClient.contenidoPrimerElemento(xml,"statusCode"));
    }

    @Test
    void rechazaDoctypeYEntidadesExternas() {
        assertThrows(IllegalStateException.class,() -> SunatSoapClient.parseSeguro(
            "<!DOCTYPE x [<!ENTITY pwd SYSTEM 'file:///etc/passwd'>]><x>&pwd;</x>"));
    }

    @Test
    void escapaCredencialesParaNoInyectarXml() {
        assertEquals("&lt;&amp;&gt;&quot;&apos;",SunatSoapClient.escape("<&>\"'"));
    }

    @Test
    void rechazaNombreYZipInvalidosSinConectarseASunat() {
        var client = new SunatSoapClient();
        assertThrows(IllegalArgumentException.class,
            () -> client.enviarResumen(SunatSoapClient.Ambiente.BETA,
                "20123456789","MODDATOS","MODDATOS",
                "archivo.zip",new byte[]{0,1,2,3}));
        assertThrows(IllegalArgumentException.class,
            () -> client.enviarResumen(SunatSoapClient.Ambiente.BETA,
                "20123456789","MODDATOS","MODDATOS",
                "20123456789-RC-20261105-1.zip",new byte[]{0,1,2,3}));
    }
}
