package pe.jmaromas.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Gestión administrativa de todos los pedidos, independientemente del medio de pago.
 * Las rutas /api/v1/admin/** ya exigen credenciales y rol ADMIN en AdminAuthFilter.
 * "CONFIRMADO" significa aceptación comercial, NO que se haya recibido dinero.
 */
@RestController
@RequestMapping("/api/v1/admin/pedidos-whatsapp")
public class PedidosWhatsappAdminController {
    private final JdbcTemplate jdbc;
    public PedidosWhatsappAdminController(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @GetMapping
    public List<Map<String,Object>> listar() {
        return jdbc.queryForList("""
          select p.id,p.codigo,p.estado,p.estado_pago as "estadoPago",
                 p.total,p.subtotal,p.descuento_total as "descuentoTotal",
                 p.costo_entrega as "costoEntrega",
                 (select exists(select 1 from app_private.comprobantes_pago cp
                   where cp.pedido_id=p.id)) as "tieneEvidencia",
                 (select cp.estado from app_private.comprobantes_pago cp
                   where cp.pedido_id=p.id order by cp.creado_en desc limit 1)
                     as "estadoEvidencia",
                 (select cp.creado_en from app_private.comprobantes_pago cp
                   where cp.pedido_id=p.id order by cp.creado_en desc limit 1)
                     as "evidenciaEn",
                 p.metodo_entrega as "metodoEntrega",
                 p.agencia_shalom as "agenciaShalom",
                 p.zona_entrega_id as "zonaEntregaId",
                 p.descripcion_entrega as "descripcionEntrega",
                 p.metodo_pago as "metodoPago",
                 p.creado_en as "creadoEn",p.expira_en as "expiraEn",
                 p.cliente_snapshot->>'nombre' as "clienteNombre",
                 p.cliente_snapshot->>'telefono' as "clienteTelefono",
                 p.direccion_snapshot->>'distrito' as "distrito",
                 p.direccion_snapshot->>'departamento' as "departamento",
                 p.direccion_snapshot->>'provincia' as "provincia",
                 p.direccion_snapshot->>'linea_direccion' as "direccion",
                 p.direccion_snapshot->>'referencia' as "referencia",
                 (select coalesce(json_agg(json_build_object(
                     'nombre',l.nombre_snapshot,'cantidad',l.cantidad,
                     'total',l.total_linea) order by l.nombre_snapshot),'[]'::json)
                  from public.detalles_pedido l where l.pedido_id=p.id) as articulos
          from public.pedidos p
          order by p.creado_en desc limit 100
          """);
    }


    @GetMapping("/{id}/lineas")
    public List<Map<String,Object>> lineas(@PathVariable UUID id) {
        Boolean existe=jdbc.queryForObject("""
            select exists(select 1 from public.pedidos
             where id=?)
            """,Boolean.class,id);
        if(!Boolean.TRUE.equals(existe))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pedido inexistente");
        return jdbc.queryForList("""
            select nombre_snapshot as "nombre",sku_snapshot as "sku",
                   cantidad,precio_unitario as "precioOriginal",
                   descuento_unitario as "descuentoUnitario",
                   total_linea as "totalLinea"
            from public.detalles_pedido where pedido_id=?
            order by nombre_snapshot
            """,id);
    }

    public record EstadoForm(String estado, String observacion) {}

    public record PagoVerificado(Boolean confirmado, String observacion) {}

    /**
     * El admin registra un cobro REAL verificado por fuera de WhatsApp.
     * Nunca supone que enviar un mensaje equivale a pagar.
     */
    @PatchMapping("/{id}/pago-verificado")
    @Transactional
    public ResponseEntity<Map<String,Object>> verificarPago(@PathVariable UUID id,
            @RequestBody PagoVerificado form,HttpServletRequest request) {
        if(form==null || !Boolean.TRUE.equals(form.confirmado()))
            throw new IllegalArgumentException("Debes confirmar expresamente que el pago fue recibido");
        List<Map<String,Object>> pedidos=jdbc.queryForList("""
            select estado,estado_pago,expira_en from public.pedidos
            where id=? and metodo_pago='COORDINAR_WHATSAPP' for update
            """,id);
        if(pedidos.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Pedido inexistente");
        String estado=(String)pedidos.getFirst().get("estado");
        String estadoPago=(String)pedidos.getFirst().get("estado_pago");
        if("PENDIENTE".equals(estado) &&
            pedidos.getFirst().get("expira_en") instanceof java.sql.Timestamp expira &&
            expira.toInstant().isBefore(java.time.Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "La reserva venció; no se puede confirmar el pago");
        if(List.of("CANCELADO","ENTREGADO","FINALIZADO").contains(estado) ||
            !"PENDIENTE".equals(estadoPago))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El pedido ya se pagó, fue cancelado o no admite confirmación");
        UUID admin=(UUID)request.getAttribute("adminUserId");
        String observacion=form.observacion()==null?"":form.observacion().strip();
        if(observacion.length()>300)observacion=observacion.substring(0,300);
        jdbc.update("""
            update public.pedidos set estado_pago='PAGADO',
             confirmado_pago_por=?,confirmacion_pago_en=now(),
             expira_en=null,actualizado_en=now()
            where id=?
            """,admin,id);
        jdbc.update("""
            insert into public.historial_pedido(
              pedido_id,estado_anterior,estado_nuevo,observacion,actualizado_por)
            values(?,?,?,?,?)
            """,id,estado,estado,
            "Pago externo confirmado manualmente"+(observacion.isEmpty()?"":": "+observacion),admin);
        return ResponseEntity.ok(Map.of("estadoPago","PAGADO"));
    }


    @PatchMapping("/{id}/estado")
    @Transactional
    public ResponseEntity<Map<String,Object>> actualizar(
        @PathVariable UUID id,@RequestBody EstadoForm data,HttpServletRequest request) {
        if(data==null || data.estado()==null ||
           !List.of("CONTACTADO","CONFIRMADO","PREPARANDO","EN_CAMINO","FINALIZADO","CANCELADO")
             .contains(data.estado()))
            throw new IllegalArgumentException("Estado no válido");
        List<Map<String,Object>> p=jdbc.queryForList("""
           select estado,estado_pago,expira_en from public.pedidos
           where id=? for update
           """,id);
        if(p.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Pedido inexistente");
        String actual=(String)p.getFirst().get("estado");
        String nuevo=data.estado();
        if("PENDIENTE".equals(actual) &&
            p.getFirst().get("expira_en") instanceof java.sql.Timestamp expira &&
            expira.toInstant().isBefore(java.time.Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "La reserva del pedido ha vencido y debe cancelarse por expiración");
        if(actual.equals(nuevo))
            return ResponseEntity.ok(Map.of("estado",actual));
        boolean permitido=switch(actual) {
            case "PENDIENTE" -> List.of("CONTACTADO","CONFIRMADO","CANCELADO").contains(nuevo);
            case "CONTACTADO" -> List.of("CONFIRMADO","CANCELADO").contains(nuevo);
            case "CONFIRMADO" -> List.of("PREPARANDO","CANCELADO").contains(nuevo);
            case "PREPARANDO" -> List.of("EN_CAMINO","CANCELADO").contains(nuevo);
            case "EN_CAMINO" -> "FINALIZADO".equals(nuevo);
            default -> false;
        };
        if(!permitido)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Transición de estado no permitida");
        if("CANCELADO".equals(nuevo) &&
            "PAGADO".equals(p.getFirst().get("estado_pago")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El pedido ya está pagado. Gestiona la devolución antes de cancelarlo");
        if(List.of("EN_CAMINO","FINALIZADO").contains(nuevo) &&
            !"PAGADO".equals(p.getFirst().get("estado_pago"))) {
            // Entregar una venta no implica cobrarla. La confirmación de pago es otro proceso.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No se puede despachar o finalizar sin confirmar el pago");
        }
        UUID admin=(UUID)request.getAttribute("adminUserId");
        if("CANCELADO".equals(nuevo)){
            List<Map<String,Object>> lineas=jdbc.queryForList("""
                select producto_id,cantidad from public.detalles_pedido
                where pedido_id=? and producto_id is not null order by producto_id
                """,id);
            for(Map<String,Object> l:lineas) {
                UUID producto=(UUID)l.get("producto_id");
                int cantidad=((Number)l.get("cantidad")).intValue();
                List<Integer> stocks=jdbc.query("select stock from public.productos where id=? for update",
                    (rs,n)->rs.getInt(1),producto);
                if(stocks.isEmpty())continue;
                int anterior=stocks.getFirst();
                int posterior=Math.addExact(anterior,cantidad);
                jdbc.update("update public.productos set stock=?,actualizado_en=now() where id=?",
                    posterior,producto);
                jdbc.update("""
                  insert into public.movimientos_stock
                   (producto_id,pedido_id,cantidad_delta,stock_anterior,stock_nuevo,motivo)
                  values(?,?,?,?,?,'PEDIDO_CANCELADO')
                  """,producto,id,cantidad,anterior,posterior);
            }
        }
        String observacion=data.observacion()==null?"":data.observacion().strip();
        if(observacion.length()>300) observacion=observacion.substring(0,300);
        jdbc.update("""
          update public.pedidos set estado=?,expira_en=null,actualizado_en=now(),
            estado_pago=case when ?='CANCELADO' then 'ANULADO' else estado_pago end
          where id=?
          """,nuevo,nuevo,id);
        jdbc.update("""
          insert into public.historial_pedido(
            pedido_id,estado_anterior,estado_nuevo,observacion,actualizado_por)
          values(?,?,?,?,?)
          """,id,actual,nuevo,
          "Gestión administrativa: "+(observacion.isBlank()?nuevo:observacion),admin);
        return ResponseEntity.ok(Map.of("estado",nuevo));
    }
}
