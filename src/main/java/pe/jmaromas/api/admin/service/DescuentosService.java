package pe.jmaromas.api.admin.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DescuentosService {
    private final JdbcTemplate jdbc;
    public DescuentosService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Formulario(String nombre,String tipo,BigDecimal valor,
                             OffsetDateTime inicio,OffsetDateTime fin) {}

    public List<Map<String,Object>> listar(UUID productoId) {
        productoPrecio(productoId,false);
        return jdbc.queryForList("""
            select id,nombre,tipo,valor,inicio,fin,activo,creado_en as "creadoEn"
            from public.descuentos where producto_id=?
            order by inicio desc,creado_en desc
            """,productoId);
    }

    @Transactional
    public ResponseEntity<?> crear(UUID productoId,Formulario datos) {
        if(datos==null || datos.nombre()==null || datos.nombre().isBlank() ||
           datos.nombre().length()>120 || datos.tipo()==null ||
           !List.of("PORCENTAJE","MONTO").contains(datos.tipo()) ||
           datos.valor()==null || datos.valor().signum()<=0 || datos.valor().scale()>2 ||
           datos.inicio()==null || datos.fin()==null ||
           !datos.fin().isAfter(datos.inicio()) ||
           datos.inicio().plusYears(3).isBefore(datos.fin()))
            throw new IllegalArgumentException("Verifica nombre, descuento y fechas válidas");
        BigDecimal precio=productoPrecio(productoId,true); // serializa promociones del producto
        if(datos.tipo().equals("PORCENTAJE") &&
            datos.valor().compareTo(new BigDecimal("100"))>=0)
            throw new IllegalArgumentException("El porcentaje debe ser menor que 100");
        if(datos.tipo().equals("MONTO") && datos.valor().compareTo(precio)>=0)
            throw new IllegalArgumentException("El descuento fijo debe ser menor que el precio base");
        Boolean solapa=jdbc.queryForObject("""
            select exists(select 1 from public.descuentos
              where producto_id=? and activo=true and inicio<? and fin>?)
            """,Boolean.class,productoId,datos.fin(),datos.inicio());
        if(Boolean.TRUE.equals(solapa))
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error","Ya existe un descuento activo en esas fechas. Desactívalo antes."));
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into public.descuentos(id,producto_id,nombre,tipo,valor,inicio,fin,activo)
            values(?,?,?,?,?,?,?,true)
            """,id,productoId,datos.nombre().strip(),datos.tipo(),
            datos.valor(),datos.inicio(),datos.fin());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id));
    }

    @Transactional
    public ResponseEntity<Void> desactivar(UUID productoId,
                                           UUID descuentoId) {
        productoPrecio(productoId,true);
        int filas=jdbc.update("""
            update public.descuentos set activo=false where id=? and producto_id=?
            """,descuentoId,productoId);
        return filas==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();
    }

    private BigDecimal productoPrecio(UUID id,boolean bloquear) {
        List<BigDecimal> datos=jdbc.query(
            "select precio from public.productos where id=?"+(bloquear?" for update":""),
            (rs,n)->rs.getBigDecimal(1),id);
        if(datos.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "El producto no existe");
        return datos.getFirst();
    }
}
