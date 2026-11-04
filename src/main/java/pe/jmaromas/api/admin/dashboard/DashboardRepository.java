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
                select (coalesce(confirmacion_pago_en,creado_en)
                    at time zone 'America/Lima')::date as fecha,
                    sum(total) as ingresos,
                    count(*)::integer as cantidad
                from public.pedidos
                where estado_pago='PAGADO'
                    and coalesce(confirmacion_pago_en,creado_en)>=?
                group by 1
            )
            select to_char(d.fecha,'YYYY-MM-DD') as "fecha",
                coalesce(p.ingresos,0) as "ingresos",
                coalesce(p.cantidad,0)::integer as "pedidosPagados"
            from dias d left join pagos p on p.fecha=d.fecha
            order by d.fecha
            """,Date.valueOf(inicio),Date.valueOf(hoy),desde));

        resultado.put("metodos",jdbc.queryForList("""
            select metodo_pago as "metodo",
                count(*)::integer as "cantidad",
                coalesce(sum(total),0) as "monto"
            from public.pedidos
            where estado_pago='PAGADO'
              and coalesce(confirmacion_pago_en,creado_en)>=?
            group by metodo_pago order by "monto" desc
            """,desde));

        resultado.put("masVendidos",jdbc.queryForList("""
            select l.nombre_snapshot as "nombre",l.sku_snapshot as "sku",
                sum(l.cantidad)::integer as "unidades",
                coalesce(sum(l.total_linea),0) as "ingresos"
            from public.detalles_pedido l
            join public.pedidos p on p.id=l.pedido_id
            where p.estado_pago='PAGADO'
              and coalesce(p.confirmacion_pago_en,p.creado_en)>=?
            group by l.nombre_snapshot,l.sku_snapshot
            order by "unidades" desc,"ingresos" desc
            limit 5
            """,desde));

        resultado.put("stockCritico",jdbc.queryForList("""
            select nombre,sku,stock,publicado
            from public.productos
            where activo=true and stock<=5
            order by stock asc,nombre asc limit 6
            """));

        resultado.put("pedidosRecientes",jdbc.queryForList("""
            select codigo,metodo_pago as "metodoPago",estado,
                estado_pago as "estadoPago",total,creado_en as "creadoEn"
            from public.pedidos order by creado_en desc limit 8
            """));
        return resultado;
    }
}
