package pe.jmaromas.api.admin.dashboard;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Consultas de solo lectura para la administración, sin nombres ni datos de compradores. */
@Repository
public class DashboardRepository {
    private static final ZoneId LIMA=ZoneId.of("America/Lima");
    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> resumen(int dias) {
        LocalDate hoy=LocalDate.now(LIMA);
        LocalDate inicio=hoy.minusDays(dias-1);
        Timestamp desde=Timestamp.from(inicio.atStartOfDay(LIMA).toInstant());

        Map<String,Object> resultado=new LinkedHashMap<>();
        resultado.put("periodoDias",dias);
        resultado.put("fechaInicio",inicio.toString());
        resultado.put("fechaFin",hoy.toString());
        resultado.put("zonaHoraria","America/Lima");

        resultado.put("pedidos",jdbc.queryForMap("""
            select
                count(*) filter(where creado_en>=?)::integer as "pedidosPeriodo",
                count(*) filter(where estado_pago='PAGADO'
                    and coalesce(confirmacion_pago_en,creado_en)>=?)::integer as "pedidosPagados",
                coalesce(sum(total) filter(where estado_pago='PAGADO'
                    and coalesce(confirmacion_pago_en,creado_en)>=?),0) as "ingresosConfirmados",
                count(*) filter(where creado_en>=?
                    and estado not in ('CANCELADO','VENCIDO')
                    and estado_pago not in ('PAGADO','ANULADO'))::integer as "pendientes",
                count(*) filter(where creado_en>=?
                    and estado in ('CANCELADO','VENCIDO'))::integer as "cancelados"
            from public.pedidos
            """,desde,desde,desde,desde,desde));

        // Las ventas de mostrador ya están cobradas al confirmarse y viven en app_private.
        // Mantener los contadores de "pedidos" exclusivos de la web para no mezclar estados.
        resultado.put("ventasPresenciales",jdbc.queryForMap("""
            select count(*)::integer as "cantidad",
                   coalesce(sum(total),0) as "ingresos"
            from app_private.ventas_presenciales
            where creado_en>=?
            """,desde));

        resultado.put("inventario",jdbc.queryForMap("""
            select count(*) filter(where activo=true)::integer as "productosActivos",
                count(*) filter(where activo=true and publicado=true)::integer as "publicados",
                count(*) filter(where activo=true and stock>0 and stock<=5)::integer as "stockBajo",
                count(*) filter(where activo=true and stock<=0)::integer as "sinStock",
                coalesce(sum(greatest(stock,0)*precio) filter(where activo=true),0) as "valorPrecioLista"
            from public.productos
            """));

        resultado.put("ventasDiarias",jdbc.queryForList("""
            with dias as (
                select fecha::date as fecha
                from generate_series(?::date,?::date,interval '1 day') as fecha
            ), pagos as (
                select fecha, sum(total) as ingresos, count(*)::integer as cantidad
                from (
                    select (coalesce(confirmacion_pago_en,creado_en)
                        at time zone 'America/Lima')::date as fecha, total
                    from public.pedidos
                    where estado_pago='PAGADO'
                      and estado not in ('CANCELADO','VENCIDO')
                      and coalesce(confirmacion_pago_en,creado_en)>=?
                    union all
                    select (creado_en at time zone 'America/Lima')::date as fecha, total
                    from app_private.ventas_presenciales where creado_en>=?
                ) cobros
                group by fecha
            )
            select to_char(d.fecha,'YYYY-MM-DD') as "fecha",
                coalesce(p.ingresos,0) as "ingresos",
                coalesce(p.cantidad,0)::integer as "pedidosPagados"
            from dias d left join pagos p on p.fecha=d.fecha
            order by d.fecha
            """,Date.valueOf(inicio),Date.valueOf(hoy),desde,desde));

        resultado.put("metodos",jdbc.queryForList("""
            select metodo_pago as "metodo", count(*)::integer as "cantidad",
                   coalesce(sum(total),0) as "monto"
            from (
                select metodo_pago, total
                from public.pedidos
                where estado_pago='PAGADO'
                  and estado not in ('CANCELADO','VENCIDO')
                  and coalesce(confirmacion_pago_en,creado_en)>=?
                union all
                select medio_pago as metodo_pago, total
                from app_private.ventas_presenciales where creado_en>=?
            ) cobros
            group by metodo_pago order by "monto" desc
            """,desde,desde));

        resultado.put("masVendidos",jdbc.queryForList("""
            select nombre as "nombre",sku as "sku",
                sum(cantidad)::integer as "unidades",
                coalesce(sum(total_linea),0) as "ingresos"
            from (
                select l.nombre_snapshot as nombre,l.sku_snapshot as sku,
                       l.cantidad,l.total_linea
                from public.detalles_pedido l
                join public.pedidos p on p.id=l.pedido_id
                where p.estado_pago='PAGADO'
                  and p.estado not in ('CANCELADO','VENCIDO')
                  and coalesce(p.confirmacion_pago_en,p.creado_en)>=?
                union all
                select d.nombre_snapshot,d.sku_snapshot,d.cantidad,d.total_linea
                from app_private.ventas_presenciales_detalle d
                join app_private.ventas_presenciales v on v.id=d.venta_id
                where v.creado_en>=?
            ) lineas
            group by nombre,sku
            order by "unidades" desc,"ingresos" desc
            limit 5
            """,desde,desde));

        resultado.put("stockCritico",jdbc.queryForList("""
            select nombre,sku,stock,publicado
            from public.productos
            where activo=true and stock<=5
            order by stock asc,nombre asc limit 6
            """));

        resultado.put("pedidosRecientes",jdbc.queryForList("""
            select codigo, "metodoPago", estado, "estadoPago", total, "creadoEn", canal
            from (
                select codigo,metodo_pago as "metodoPago",estado,
                       estado_pago as "estadoPago",total,creado_en as "creadoEn",
                       'WEB' as canal
                from public.pedidos
                union all
                select codigo,medio_pago as "metodoPago",
                       'FINALIZADO' as estado,'PAGADO' as "estadoPago",
                       total,creado_en as "creadoEn",'PRESENCIAL' as canal
                from app_private.ventas_presenciales
            ) actividad
            order by "creadoEn" desc limit 8
            """));
        return resultado;
    }
}
