package pe.jmaromas.api.admin;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Supervisión de la preparación para emitir boletas electrónicas desde JM Aromas.
 *
 * Ningún método emite comprobantes, genera numeración tributaria ni envía XML
 * al servidor de SUNAT. La emisión legal será habilitada solo después de
 * validar modalidad, certificado digital, firma, resumen diario y CDR.
 *
 * El filtro /api/v1/admin/** exige JWT y rol verificado en app_private.administradores.
 */
@RestController
@RequestMapping("/api/v1/admin/boletas/automaticas")
public class BoletasAutomaticasAdminController {
    private final JdbcTemplate jdbc;
    private final String rucEmisor;

    public BoletasAutomaticasAdminController(
        JdbcTemplate jdbc,
        @Value("${app.boletas.emisor-ruc:}") String rucEmisor
    ) {
        this.jdbc=jdbc;
        this.rucEmisor=rucEmisor.strip();
    }

    @GetMapping
    public List<Map<String,Object>> listar() {
        return jdbc.queryForList("""
            select b.id,b.pedido_id as "pedidoId",p.codigo as "pedidoCodigo",
                   p.cliente_snapshot->>'nombre' as "clienteNombre",
                   p.estado_pago as "estadoPago",p.total,b.estado,
                   b.modalidad,b.creado_en as "creadoEn",
                   b.ultimo_error as "ultimoError",
                   b.serie,b.numero,b.ticket_sunat as "ticketSunat"
              from app_private.boletas_automaticas b
              join public.pedidos p on p.id=b.pedido_id
             order by b.creado_en desc limit 100
            """);
    }

    @GetMapping("/configuracion")
    public Map<String,Object> configuracion() {
        // Estar conectado a SUNAT SOL no equivale a estar habilitado
        // para emitir documentos desde SEE del Contribuyente.
        return Map.of(
            "modo","PREPARACION",
            "emisionRealHabilitada",false,
            "registroAutomatico",true,
            "rucEmisorConfigurado",rucEmisor.matches("[0-9]{11}"),
            "serieParaSoftwareConfirmada",false,
            "certificadoDigitalConfigurado",false,
            "vinculacionSeeContribuyenteConfirmada",false,
            "transmisionResumenDiarioHabilitada",false,
            "mensaje","Las ventas pagadas se identifican automáticamente. "
                + "Aún no se crean ni transmiten boletas a SUNAT."
        );
    }

    /**
     * Vista previa de datos comerciales, NO una boleta electrónica ni un XML
     * UBL certificado. Utiliza los snapshots inmutables del pedido.
     */
    @GetMapping("/{id}/preparacion")
    public Map<String,Object> preparar(@PathVariable UUID id) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            select b.id,b.estado,p.codigo,p.estado_pago,p.es_prueba,
                   p.total,p.subtotal,p.descuento_total,p.costo_entrega,
                   p.cliente_snapshot->>'nombre' as "clienteNombre",
                   p.cliente_snapshot->>'dni' as "clienteDocumento",
                   p.cliente_snapshot->>'email' as "clienteEmail"
              from app_private.boletas_automaticas b
              join public.pedidos p on p.id=b.pedido_id
             where b.id=?
            """,id);
        if(rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No existe esa solicitud");
        Map<String,Object> registro=rows.getFirst();
        if(!"PAGADO".equals(registro.get("estado_pago")) ||
           Boolean.TRUE.equals(registro.get("es_prueba")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "La solicitud no corresponde a una venta real con pago confirmado.");

        List<Map<String,Object>> lineas=jdbc.queryForList("""
            select sku_snapshot as sku,nombre_snapshot as nombre,cantidad,
                   precio_unitario as "precioUnitario",
                   descuento_unitario as "descuentoUnitario",
                   total_linea as "totalLinea"
            from public.detalles_pedido
            where pedido_id=(select pedido_id from app_private.boletas_automaticas where id=?)
            order by nombre_snapshot
            """,id);

        BigDecimal total=(BigDecimal)registro.get("total");
        BigDecimal suma=BigDecimal.ZERO;
        for(Map<String,Object> linea:lineas)
            suma=suma.add((BigDecimal)linea.get("totalLinea"));
        BigDecimal entrega=(BigDecimal)registro.get("costo_entrega");
        if(lineas.isEmpty() || suma.add(entrega).compareTo(total)!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El importe del pedido y el detalle no coinciden. Revisa los datos antes de emitir.");

        Map<String,Object> respuesta=new LinkedHashMap<>();
        respuesta.put("id",id);
        respuesta.put("pedidoCodigo",registro.get("codigo"));
        respuesta.put("rucEmisor",rucEmisor.matches("[0-9]{11}")?rucEmisor:null);
        respuesta.put("tipoComprobante","03");
        respuesta.put("estado","DATOS_COMERCIALES_PREPARADOS");
        respuesta.put("moneda","PEN");
        respuesta.put("nombreCliente",registro.get("clienteNombre"));
        respuesta.put("documentoCliente",registro.get("clienteDocumento"));
        respuesta.put("correoCliente",registro.get("clienteEmail"));
        respuesta.put("total",total);
        respuesta.put("lineas",lineas);
        respuesta.put("advertencia",
            "Borrador comercial interno. No contiene XML UBL firmado, numeración autorizada, "
            + "CDR ni validación tributaria. No debe entregarse como boleta.");
        return respuesta;
    }
}
