package pe.jmaromas.api.checkout;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservasVencidasJob {
    private final JdbcTemplate jdbc;
    public ReservasVencidasJob(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    // La reserva de stock es temporal. Cuando vence sin comprobante aceptado,
    // se cancela la orden y se devuelve el inventario dentro de la misma transacción.
    @Scheduled(fixedDelay=300000, initialDelay=30000)
    @Transactional
    public void liberarVencidas() {
        List<UUID> pedidos=jdbc.query("""
          select id from public.pedidos
          where estado='PENDIENTE' and estado_pago in ('PENDIENTE','RECHAZADO')
            and expira_en is not null and expira_en < now()
          order by expira_en
          for update skip locked
          limit 30
          """,(rs,n)->rs.getObject(1,UUID.class));
        for(UUID pedido:pedidos){
            List<Map<String,Object>> lineas=jdbc.queryForList("""
              select producto_id,cantidad from public.detalles_pedido
              where pedido_id=? and producto_id is not null order by producto_id
              """,pedido);
            for(var l:lineas){
                UUID producto=(UUID)l.get("producto_id");
                int cantidad=((Number)l.get("cantidad")).intValue();
                List<Integer> stocks=jdbc.query("""
                  select stock from public.productos where id=? for update
                  """,(rs,n)->rs.getInt(1),producto);
                if(stocks.isEmpty())continue;
                int previo=stocks.getFirst();
                int nuevo=Math.addExact(previo,cantidad);
                jdbc.update("""
                  update public.productos set stock=?,actualizado_en=now() where id=?
                  """,nuevo,producto);
                jdbc.update("""
                  insert into public.movimientos_stock
                    (producto_id,pedido_id,cantidad_delta,stock_anterior,stock_nuevo,motivo)
                  values(?,?,?,?,?,'RESERVA_EXPIRADA')
                  """,producto,pedido,cantidad,previo,nuevo);
            }
            jdbc.update("""
              update public.pedidos set estado='CANCELADO',estado_pago='ANULADO',
                expira_en=null,actualizado_en=now() where id=?
              """,pedido);
            jdbc.update("""
              insert into public.historial_pedido
                (pedido_id,estado_anterior,estado_nuevo,observacion)
              values(?,'PENDIENTE','CANCELADO',
                'Reserva vencida sin confirmación de pago; stock devuelto')
              """,pedido);
        }
    }
}
