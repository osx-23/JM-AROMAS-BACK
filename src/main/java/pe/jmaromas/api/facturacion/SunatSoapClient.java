package pe.jmaromas.api.facturacion;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Cliente SOAP 1.1 para SUNAT SEE del Contribuyente.
 *
 * No se invoca automáticamente hasta completar:
 *  - Habilitación SEE para el RUC / usuario SOL secundario.
 *  - Firma digital XML validada y UBL 2.1 acorde a su catálogo tributario.
 *  - Resumen diario 2.0 firmado y correlativos oficiales.
 *  - Manejo persistente y seguro de ticket / CDR / reintentos.
 *
 * Este componente NUNCA asigna números ni declara una boleta emitida.
 * No escribir en logs las credenciales o el mensaje SOAP.
 */
@Component
public class SunatSoapClient {
    public enum Ambiente { BETA, PRODUCCION }

    private static final URI BETA =
        URI.create("https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService");
    private static final URI PRODUCCION =
        URI.create("https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public record ResumenTicket(String ticket) {}
    public record ConsultaTicket(String codigo, String cdrZipBase64) {
        public boolean incluyeCdr() { return cdrZipBase64 != null && !cdrZipBase64.isBlank(); }
    }

    /**
     * Envía un ZIP con el resumen diario XML firmado mediante sendSummary.
     * Devolver un ticket NO implica boleta aceptada por SUNAT.
     */
    public ResumenTicket enviarResumen(
        Ambiente ambiente, String ruc, String usuarioSecundario,
        String contrasena, String nombreZip, byte[] zipFirmado
    ) {
        if(nombreZip == null ||
            !nombreZip.matches("^[0-9]{11}-RC-[0-9]{8}-[0-9]+[.]zip$"))
            throw new IllegalArgumentException("Nombre de resumen diario RC inválido");
        validarZip(zipFirmado);
        String body = "<ser:sendSummary>"
            + "<fileName>" + escape(nombreZip) + "</fileName>"
            + "<contentFile>" + Base64.getEncoder().encodeToString(zipFirmado) + "</contentFile>"
            + "</ser:sendSummary>";
        Document response = post(ambiente,ruc,usuarioSecundario,contrasena,body);
        String ticket = contenidoPrimerElemento(response,"ticket");
        if(ticket == null || ticket.isBlank())
            throw new IllegalStateException("SUNAT no devolvió ticket de recepción");
        return new ResumenTicket(ticket);
    }

    /**
     * Consulta getStatus. El código del ticket se debe interpretar con el CDR
     * firmado por SUNAT antes de declarar el comprobante aceptado.
     */
    public ConsultaTicket consultarTicket(
        Ambiente ambiente,String ruc,String usuarioSecundario,
        String contrasena,String ticket
    ) {
        if(ticket == null || !ticket.matches("^[A-Za-z0-9_-]{4,60}$"))
            throw new IllegalArgumentException("Ticket SUNAT inválido");
        Document response = post(ambiente,ruc,usuarioSecundario,contrasena,
            "<ser:getStatus><ticket>" + escape(ticket) + "</ticket></ser:getStatus>");
        String codigo = contenidoPrimerElemento(response,"statusCode");
        if(codigo == null || codigo.isBlank())
            throw new IllegalStateException("SUNAT no devolvió estado del ticket");
        return new ConsultaTicket(codigo,contenidoPrimerElemento(response,"content"));
    }

    private Document post(Ambiente ambiente,String ruc,String usuario,
                          String password,String body) {
        if(ambiente==null) throw new IllegalArgumentException("Selecciona ambiente SUNAT");
        if(ruc==null || !ruc.matches("^[0-9]{11}$") ||
           usuario==null || usuario.isBlank() || usuario.length()>80 ||
           password==null || password.isBlank())
            throw new IllegalArgumentException("Credenciales SUNAT no configuradas");
        String envelope = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:ser="http://service.sunat.gob.pe"
              xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <soapenv:Header><wsse:Security><wsse:UsernameToken>
                <wsse:Username>%s</wsse:Username>
                <wsse:Password>%s</wsse:Password>
              </wsse:UsernameToken></wsse:Security></soapenv:Header>
              <soapenv:Body>%s</soapenv:Body>
            </soapenv:Envelope>
            """.formatted(escape(ruc+usuario),escape(password),body);

        URI destino=ambiente==Ambiente.PRODUCCION?PRODUCCION:BETA;
        HttpRequest request=HttpRequest.newBuilder(destino)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type","text/xml; charset=utf-8")
            .header("SOAPAction","")
            .POST(HttpRequest.BodyPublishers.ofString(envelope,StandardCharsets.UTF_8))
            .build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200)
                throw new IllegalStateException("SUNAT respondió HTTP "+response.statusCode()
                    + ". No reintentar sin consultar si el envío fue recibido.");
            Document xml=parseSeguro(response.body());
            if(contenidoPrimerElemento(xml,"Fault")!=null)
                throw new IllegalStateException("SUNAT rechazó la solicitud SOAP; consultar diagnóstico seguro.");
            return xml;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Comunicación SUNAT interrumpida. Verificar el estado antes de reintentar.");
        }catch(java.io.IOException e){
            throw new IllegalStateException(
                "No fue posible contactar SUNAT. El resultado es incierto; no generar otro correlativo.");
        }
    }

    static Document parseSeguro(String xml) {
        try {
            if(xml == null || xml.length()>8_000_000)
                throw new IllegalArgumentException("Respuesta XML vacía o excesiva");
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e) {
            throw new IllegalStateException("Respuesta SUNAT no contiene XML seguro válido");
        }
    }

    static String contenidoPrimerElemento(Document xml,String localName) {
        NodeList nodes=xml.getElementsByTagNameNS("*",localName);
        if(nodes.getLength()==0) return null;
        Node n=nodes.item(0);
        return localName.equals("Fault") ? "SOAP Fault" : n.getTextContent().trim();
    }

    static String escape(String texto) {
        if(texto == null) return "";
        return texto.replace("&","&amp;").replace("<","&lt;")
            .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }

    private static void validarZip(byte[] bytes) {
        if(bytes==null || bytes.length<24 || bytes.length>2_500_000 ||
           bytes[0]!=0x50 || bytes[1]!=0x4b ||
           bytes[2]!=0x03 || bytes[3]!=0x04)
            throw new IllegalArgumentException("Archivo ZIP firmado inválido o demasiado grande");
        // Se debe validar XML, firma y CRC del ZIP antes de llamar a este cliente.
    }
}
