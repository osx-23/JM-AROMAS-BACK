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
        return resumen(inicio,hoy);
    }

    /** Todos los indicadores usan el mismo rango inclusivo de fechas locales de Lima. */
    public Map<String,Object> resumen(LocalDate inicio,LocalDate fin) {
        Timestamp desde=Timestamp.from(inicio.atStartOfDay(LIMA).toInstant());
        Timestamp hasta=Timestamp.from(fin.plusDays(1).atStartOfDay(LIMA).toInstant());

        Map<String,Object> resultado=new LinkedHashMap<>();
        resultado.put("periodoDias",java.time.temporal.ChronoUnit.DAYS.between(inicio,fin)+1);
        resultado.put("fechaInicio",inicio.toString());
        resultado.put("fechaFin",fin.toString());
        resultado.put("zonaHoraria","America/Lima");

        resultado.put("pedidos",jdbc.queryForMap("""
            select
                count(*) filter(where creado_en>=? and creado_en<?)::integer as "pedidosPeriodo",
                count(*) filter(where estado_pago='PAGADO'
                    and coalesce(confirmacion_pago_en,creado_en)>=?
                    and coalesce(confirmacion_pago_en,creado_en)<?
                    and estado not in ('CANCELADO','VENCIDO'))::integer as "pedidosPagados",
                coalesce(sum(total) filter(where estado_pago='PAGADO'
                    and coalesce(confirmacion_pago_en,creado_en)>=?
                    and coalesce(confirmacion_pago_en,creado_en)<?
                    and estado not in ('CANCELADO','VENCIDO')),0) as "ingresosConfirmados",
                count(*) filter(where creado_en>=? and creado_en<?
                    and estado not in ('CANCELADO','VENCIDO')
                    and estado_pago not in ('PAGADO','ANULADO'))::integer as "pendientes",
                count(*) filter(where creado_en>=? and creado_en<?
                    and estado in ('CANCELADO','VENCIDO'))::integer as "cancelados"
            from public.pedidos
            """,desde,hasta,desde,hasta,desde,hasta,desde,hasta,desde,hasta));

        // Las ventas de mostrador ya están cobradas al confirmarse y viven en app_private.
        // Mantener los contadores de "pedidos" exclusivos de la web para no mezclar estados.
        resultado.put("ventasPresenciales",jdbc.queryForMap("""
            select count(*)::integer as "cantidad",
                   coalesce(sum(total),0) as "ingresos"
            from app_private.ventas_presenciales
            where creado_en>=? and creado_en<?
            """,desde,hasta));

        // Ingreso NETO de productos (sin delivery), costo registrado en el momento
        // de cada venta y descuentos ya reflejados en total_linea.
        // Si falta cualquier costo histórico se devuelve NULL, nunca una ganancia ficticia.
        resultado.put("rentabilidad",jdbc.queryForMap("""
            with operaciones as (
              select l.total_linea as ingreso, l.cantidad,
                     c.costo_unitario_snapshot as costo
              from public.detalles_pedido l
              join public.pedidos p on p.id=l.pedido_id
              left join app_private.costos_detalle_pedido c
                on c.pedido_id=l.pedido_id and c.producto_id=l.producto_id
              where p.estado_pago='PAGADO'
                and p.estado not in ('CANCELADO','VENCIDO')
                and coalesce(p.confirmacion_pago_en,p.creado_en)>=?
                and coalesce(p.confirmacion_pago_en,p.creado_en)<?
              union all
              select d.total_linea as ingreso,d.cantidad,
                     d.costo_unitario_snapshot as costo
              from app_private.ventas_presenciales_detalle d
              join app_private.ventas_presenciales v on v.id=d.venta_id
              where v.creado_en>=? and v.creado_en<?
            ), totales as (
              select coalesce(sum(ingreso),0) as ingresos_productos,
                     coalesce(sum(cantidad*costo),0) as costos,
                     count(*) filter(where costo is null)::integer as faltantes
              from operaciones
            )
            select ingresos_productos as "ingresosProductos",
                   faltantes as "lineasSinCosto",
                   case when faltantes>0 then null
                     else ingresos_productos-costos end as "gananciaBruta",
                   case when faltantes>0 then null
                     when ingresos_productos=0 then 0
                     else round(100*(ingresos_productos-costos)/ingresos_productos,2)
                   end as "margenBrutoPct"
            from totales
            """,desde,hasta,desde,hasta));

        // Comparación entre el mes calendario de la fecha final elegida
        // y el mes calendario anterior; ambos usan la zona horaria de Lima.
        java.time.YearMonth mesActual=java.time.YearMonth.from(fin);
        java.time.YearMonth mesAnterior=mesActual.minusMonths(1);
        Timestamp desdeAnterior=Timestamp.from(mesAnterior.atDay(1).atStartOfDay(LIMA).toInstant());
        Timestamp inicioActual=Timestamp.from(mesActual.atDay(1).atStartOfDay(LIMA).toInstant());
        Timestamp finActual=Timestamp.from(mesActual.plusMonths(1).atDay(1).atStartOfDay(LIMA).toInstant());
        Map<String,Object> comparacion=new LinkedHashMap<>();
        comparacion.put("mesActual",mesActual.toString());
        comparacion.put("mesAnterior",mesAnterior.toString());
        comparacion.putAll(jdbc.queryForMap("""
            with cobros as (
              select total,coalesce(confirmacion_pago_en,creado_en) as fecha
              from public.pedidos
              where estado_pago='PAGADO' and estado not in ('CANCELADO','VENCIDO')
                and coalesce(confirmacion_pago_en,creado_en)>=?
                and coalesce(confirmacion_pago_en,creado_en)<?
              union all
              select total,creado_en as fecha from app_private.ventas_presenciales
              where creado_en>=? and creado_en<?
            ), totales as (
              select coalesce(sum(total) filter(where fecha>=?),0) as actual,
                     coalesce(sum(total) filter(where fecha<?),0) as anterior
              from cobros
            )
            select actual as "ingresosMesActual",
                   anterior as "ingresosMesAnterior",
                   case when anterior=0 then null
                     else round(100*(actual-anterior)/anterior,2)
                   end as "variacionPct"
            from totales
            """,desdeAnterior,finActual,desdeAnterior,finActual,inicioActual,inicioActual));
        resultado.put("comparacionMensual",comparacion);

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
                      and coalesce(confirmacion_pago_en,creado_en)<?
                    union all
                    select (creado_en at time zone 'America/Lima')::date as fecha, total
                    from app_private.ventas_presenciales where creado_en>=? and creado_en<?
                ) cobros
                group by fecha
            )
            select to_char(d.fecha,'YYYY-MM-DD') as "fecha",
                coalesce(p.ingresos,0) as "ingresos",
                coalesce(p.cantidad,0)::integer as "pedidosPagados"
            from dias d left join pagos p on p.fecha=d.fecha
            order by d.fecha
            """,Date.valueOf(inicio),Date.valueOf(fin),desde,hasta,desde,hasta));

        resultado.put("metodos",jdbc.queryForList("""
            select metodo_pago as "metodo", count(*)::integer as "cantidad",
                   coalesce(sum(total),0) as "monto"
            from (
                select metodo_pago, total
                from public.pedidos
                where estado_pago='PAGADO'
                  and estado not in ('CANCELADO','VENCIDO')
                  and coalesce(confirmacion_pago_en,creado_en)>=?
                  and coalesce(confirmacion_pago_en,creado_en)<?
                union all
                select medio_pago as metodo_pago, total
                from app_private.ventas_presenciales where creado_en>=? and creado_en<?
            ) cobros
            group by metodo_pago order by "monto" desc
            """,desde,hasta,desde,hasta));

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
                  and coalesce(p.confirmacion_pago_en,p.creado_en)<?
                union all
                select d.nombre_snapshot,d.sku_snapshot,d.cantidad,d.total_linea
                from app_private.ventas_presenciales_detalle d
                join app_private.ventas_presenciales v on v.id=d.venta_id
                where v.creado_en>=? and v.creado_en<?
            ) lineas
            group by nombre,sku
            order by "unidades" desc,"ingresos" desc
            limit 5
            """,desde,hasta,desde,hasta));

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
