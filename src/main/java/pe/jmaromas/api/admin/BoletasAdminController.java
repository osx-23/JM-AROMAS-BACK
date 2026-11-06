package pe.jmaromas.api.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registro interno de boletas YA emitidas mediante SEE-SOL.
 *
 * Ningún endpoint emite boletas en SUNAT. La autorización se aplica
 * a /api/v1/admin/** mediante AdminAuthFilter (JWT validado en Supabase
 * más pertenencia a app_private.administradores).
 */
@RestController
@RequestMapping("/api/v1/admin/boletas")
public class BoletasAdminController {
    private final JdbcTemplate jdbc;
    private final String emisorRuc;

    public BoletasAdminController(
        JdbcTemplate jdbc,
        @Value("${app.boletas.emisor-ruc:}") String emisorRuc
    ) {
        this.jdbc = jdbc;
        this.emisorRuc = emisorRuc.strip();
    }

    public record RegistrarBoleta(
        UUID pedidoId, String serie, Integer numero,
        LocalDate fechaEmision, Boolean emitidaEnSunat
    ) {}

    /** Pedidos con cobro verificado que aún no tienen boleta asociada. */
    @GetMapping("/pedidos-elegibles")
    public List<Map<String,Object>> pedidosElegibles() {
        return jdbc.queryForList("""
            select p.id, p.codigo, p.total, p.metodo_pago as "metodoPago",
                   p.estado, p.estado_pago as "estadoPago",
                   p.cliente_snapshot->>'nombre' as "clienteNombre",
                   p.cliente_snapshot->>'dni' as "clienteDni",
                   p.creado_en as "creadoEn"
            from public.pedidos p
            left join app_private.boletas_manuales b on b.pedido_id=p.id
            where p.estado_pago='PAGADO' and p.estado<>'CANCELADO'
              and b.id is null
            order by p.creado_en desc
            limit 100
            """);
    }

    /** Consultas del panel, sin revelar información privada mediante PostgREST. */
    @GetMapping
    public List<Map<String,Object>> listar() {
        return jdbc.queryForList("""
            select b.id, b.pedido_id as "pedidoId", p.codigo as "pedidoCodigo",
                   b.emisor_ruc as "emisorRuc", b.serie, b.numero,
                   b.fecha_emision as "fechaEmision", b.moneda, b.total,
                   b.modalidad, b.estado_validacion as "estadoValidacion",
                   p.cliente_snapshot->>'nombre' as "clienteNombre",
                   b.creado_en as "creadoEn"
            from app_private.boletas_manuales b
            join public.pedidos p on p.id=b.pedido_id
            order by b.creado_en desc
            limit 100
            """);
    }

    /**
     * Registra la referencia de una boleta real que el administrador
     * generó antes en SUNAT SEE-SOL. NO genera correlativos nuevos.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String,Object>> registrar(
        @RequestBody RegistrarBoleta entrada,
        HttpServletRequest request
    ) {
        if (!emisorRuc.matches("^[0-9]{11}$"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configura el RUC emisor en BOLETAS_EMISOR_RUC de Railway.");

        if (entrada == null || entrada.pedidoId() == null
            || !Boolean.TRUE.equals(entrada.emitidaEnSunat()))
            throw new IllegalArgumentException(
                "Selecciona el pedido y confirma que la boleta ya fue emitida en SUNAT.");

        String serie = entrada.serie() == null
            ? "" : entrada.serie().strip().toUpperCase(Locale.ROOT);
        if (!serie.matches("^[A-Z0-9]{4}$"))
            throw new IllegalArgumentException("La serie debe tener cuatro caracteres, por ejemplo EB01.");
        if (entrada.numero() == null || entrada.numero() < 1)
            throw new IllegalArgumentException("Introduce el número correlativo de la boleta SUNAT.");
        if (entrada.fechaEmision() == null ||
            entrada.fechaEmision().isAfter(LocalDate.now(ZoneId.of("America/Lima"))))
            throw new IllegalArgumentException("La fecha de emisión es obligatoria y no puede ser futura.");

        Object adminId = request.getAttribute("adminUserId");
        if (!(adminId instanceof UUID administrador))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sesión de administrador requerida.");

        List<Map<String,Object>> pedidos = jdbc.queryForList("""
            select codigo, estado, estado_pago, total
            from public.pedidos where id=? for update
            """,entrada.pedidoId());
        if (pedidos.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado.");

        var pedido = pedidos.getFirst();
        if (!"PAGADO".equals(pedido.get("estado_pago")) ||
            "CANCELADO".equals(pedido.get("estado")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Solo se pueden vincular boletas a pedidos no cancelados con pago confirmado.");

        // Si la solicitud automática ya está en emisión o fue aceptada,
        // se prohíbe registrar una boleta SEE-SOL para el mismo pedido.
        List<String> automaticos = jdbc.query("""
            select estado from app_private.boletas_automaticas
            where pedido_id=?
            for update
            """,(rs,n)->rs.getString(1),entrada.pedidoId());
        if(!automaticos.isEmpty() &&
           List.of("GENERANDO","ENVIADA","ACEPTADA").contains(automaticos.getFirst()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El pedido tiene una emisión automática en proceso o completada; revisa su estado.");

        // Dos transacciones concurrentes sobre el mismo pedido quedan serializadas
        // por el bloqueo FOR UPDATE. El UNIQUE también protege el número de boleta.
        List<UUID> insertadas = jdbc.query("""
            insert into app_private.boletas_manuales
              (pedido_id,emisor_ruc,serie,numero,fecha_emision,total,registrado_por)
            values (?,?,?,?,?,?,?)
            on conflict do nothing
            returning id
            """,(rs,n)->rs.getObject(1,UUID.class),
            entrada.pedidoId(),emisorRuc,serie,entrada.numero(),
            entrada.fechaEmision(),((BigDecimal)pedido.get("total")),administrador);

        if (insertadas.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Este pedido o la serie y número de boleta ya fueron registrados.");
        // Al vincular una boleta SEE-SOL real, desactiva la solicitud automática
        // para evitar que en el futuro se emita un segundo comprobante tributario.
        jdbc.update("""
            update app_private.boletas_automaticas
               set estado='DESCARTADA',actualizado_en=now()
             where pedido_id=? and estado in
                ('PENDIENTE_CONFIGURACION','LISTA_PARA_EMITIR','ERROR')
            """,entrada.pedidoId());

        Map<String,Object> respuesta = new LinkedHashMap<>();
        respuesta.put("id",insertadas.getFirst());
        respuesta.put("pedidoId",entrada.pedidoId());
        respuesta.put("pedidoCodigo",pedido.get("codigo"));
        respuesta.put("serie",serie);
        respuesta.put("numero",entrada.numero());
        respuesta.put("fechaEmision",entrada.fechaEmision());
        respuesta.put("total",pedido.get("total"));
        respuesta.put("modalidad","SEE_SOL");
        respuesta.put("estadoValidacion","NO_VERIFICADA");
        respuesta.put("mensaje","Boleta registrada internamente. No equivale a validación en SUNAT.");
        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}
