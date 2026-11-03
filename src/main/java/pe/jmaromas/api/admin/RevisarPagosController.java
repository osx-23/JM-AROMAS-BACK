package pe.jmaromas.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/admin/pedidos")
public class RevisarPagosController {
    private final JdbcTemplate jdbc;
    private final RestClient storage;
    private final String serviceRoleKey;
    public RevisarPagosController(JdbcTemplate jdbc,
        @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String url,
        @Value("${app.supabase.service-role-key:}") String serviceRoleKey) {
        this.jdbc=jdbc;
        this.serviceRoleKey=serviceRoleKey;
        this.storage=RestClient.builder().baseUrl(url).build();
    }
    public record Decision(String accion,String observacion) {}

    @GetMapping("/pendientes")
    public List<Map<String,Object>> pendientes() {
        return jdbc.queryForList("""
          select p.id,p.codigo,p.metodo_pago as "metodoPago",
            p.total,p.estado,p.estado_pago as "estadoPago",
            p.cliente_snapshot::text as "clienteJson",
            p.direccion_snapshot::text as "direccionJson",p.creado_en as "creadoEn",
            c.creado_en as "comprobanteEn",
            m.titulo as "cuentaTitulo",m.titular as "cuentaTitular",
            m.banco as "cuentaBanco",m.numero_cuenta as "cuentaNumero",
            m.numero_yape as "cuentaYape",m.cci as "cuentaCci"
          from public.pedidos p
          join app_private.comprobantes_pago c on c.pedido_id=p.id
          left join app_private.medios_pago m on m.id=p.medio_pago_id
          where p.estado_pago='PENDIENTE_CONFIRMACION'
          order by c.creado_en asc limit 100
          """);
    }

    @GetMapping("/{id}/comprobante")
    public ResponseEntity<byte[]> comprobante(@PathVariable UUID id) {
        if(serviceRoleKey.isBlank())throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Storage aún no configurado");
        List<Map<String,Object>> rows=jdbc.queryForList("""
          select ruta_storage,mime from app_private.comprobantes_pago where pedido_id=?
          """,id);
        if(rows.isEmpty()) throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"No existe evidencia");
        var r=rows.getFirst();
        String path=r.get("ruta_storage").toString();
        String mime=r.get("mime").toString();
        try{
            byte[] bytes=storage.get()
                .uri("/storage/v1/object/authenticated/jm-aromas-comprobantes/"+path)
                .header("apikey",serviceRoleKey)
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceRoleKey)
                .retrieve().body(byte[].class);
            if(bytes==null) throw new IllegalStateException("Archivo vacío");
            return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType(mime))
                .body(bytes);
        }catch(Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo obtener la evidencia",e);
        }
    }

    @PatchMapping("/{id}/pago")
    @Transactional
    public ResponseEntity<Map<String,String>> decidir(@PathVariable UUID id,
        @RequestBody Decision decision,HttpServletRequest request) {
        if(decision==null || !List.of("CONFIRMAR","RECHAZAR").contains(decision.accion()))
            throw new IllegalArgumentException("Decisión requerida: CONFIRMAR o RECHAZAR");
        if(decision.accion().equals("RECHAZAR") &&
            (decision.observacion()==null || decision.observacion().isBlank()))
            throw new IllegalArgumentException("Indica el motivo del rechazo");
        List<Map<String,Object>> pedidos=jdbc.queryForList("""
          select estado_pago,estado from public.pedidos where id=? for update
          """,id);
        if(pedidos.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pedido inexistente");
        if(!"PENDIENTE_CONFIRMACION".equals(pedidos.getFirst().get("estado_pago")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El comprobante ya fue revisado o no se ha cargado");
        UUID admin=(UUID)request.getAttribute("adminUserId");
        boolean aprobado=decision.accion().equals("CONFIRMAR");
        String nuevoEstadoPago=aprobado?"PAGADO":"RECHAZADO";
        String nuevoEstadoPedido=aprobado?"CONFIRMADO":"PENDIENTE";
        int n=jdbc.update("""
          update app_private.comprobantes_pago
          set estado=?,comentario_revision=?,revisado_por=?,revisado_en=now()
          where pedido_id=? and estado='PENDIENTE_CONFIRMACION'
          """,aprobado?"CONFIRMADO":"RECHAZADO",
          decision.observacion(),admin,id);
        if(n!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Comprobante no encontrado");
        jdbc.update("""
          update public.pedidos set estado_pago=?,estado=?,confirmado_pago_por=?,
          confirmacion_pago_en=case when ? then now() else null end,
          expira_en=case when ? then null else now()+interval '24 hours' end,
          actualizado_en=now()
          where id=?
          """,nuevoEstadoPago,nuevoEstadoPedido,aprobado?admin:null,aprobado,aprobado,id);
        jdbc.update("""
          insert into public.historial_pedido
           (pedido_id,estado_anterior,estado_nuevo,observacion,actualizado_por)
          values (?,?,?,?,?)
          """,id,pedidos.getFirst().get("estado"),nuevoEstadoPedido,
          aprobado?"Abono bancario confirmado por administrador":
            "Comprobante rechazado: "+decision.observacion().strip().substring(0,
                Math.min(300,decision.observacion().strip().length())),
          admin);
        return ResponseEntity.ok(Map.of("estadoPago",nuevoEstadoPago,"estado",nuevoEstadoPedido));
    }
}
