package pe.jmaromas.api.checkout;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/checkout")
public class ComprobantePagoController {
    private final JdbcTemplate jdbc;
    private final CheckoutPublicController checkout;
    private final RestClient storage;
    private final String serviceRoleKey;

    public ComprobantePagoController(JdbcTemplate jdbc,CheckoutPublicController checkout,
        @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String url,
        @Value("${app.supabase.service-role-key:}") String serviceRoleKey) {
        this.jdbc=jdbc;
        this.checkout=checkout;
        this.serviceRoleKey=serviceRoleKey;
        this.storage=RestClient.builder().baseUrl(url).build();
    }

    @PostMapping(path="/pedidos/{id}/comprobante",
        consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> subir(@PathVariable UUID id,
        @RequestHeader(value="X-Pedido-Token",required=false) String token,
        @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @RequestParam("archivo") MultipartFile archivo) throws IOException {
        checkout.validarAccesoPedido(id,token,authorization);
        if(serviceRoleKey.isBlank()) throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Almacenamiento privado aún no configurado");
        if(archivo.isEmpty() || archivo.getSize()>5*1024*1024)
            throw new IllegalArgumentException("Sube una imagen menor a 5 MB");
        byte[] bytes=archivo.getBytes();
        String tipo=detectarMime(bytes);
        if(tipo==null) throw new IllegalArgumentException("Solo se aceptan JPG, PNG o WEBP reales");
        String extension=tipo.equals("image/jpeg")?".jpg":
                         tipo.equals("image/png")?".png":".webp";
        var filas=jdbc.queryForList("""
          select metodo_pago,estado,estado_pago,expira_en,medio_pago_id
          from public.pedidos where id=? for update
          """,id);
        if(filas.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pedido no encontrado");
        var fila=filas.getFirst();
        String metodo=fila.get("metodo_pago").toString();
        String estado=fila.get("estado").toString();
        String pago=fila.get("estado_pago").toString();
        if(!List.of("YAPE","TRANSFERENCIA").contains(metodo))
            throw new IllegalArgumentException("Este pedido no utiliza pago por la web");
        if(!estado.equals("PENDIENTE") || !List.of("PENDIENTE","RECHAZADO").contains(pago))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"No admite otro comprobante");
        if(fila.get("expira_en") instanceof java.sql.Timestamp vencimiento &&
            vencimiento.toInstant().isBefore(java.time.Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Reserva vencida; crea un pedido nuevo");

        String ruta=id+"/"+UUID.randomUUID()+extension;
        try{
            storage.post().uri("/storage/v1/object/jm-aromas-comprobantes/"+ruta)
                .header("apikey",serviceRoleKey)
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceRoleKey)
                .contentType(MediaType.parseMediaType(tipo))
                .body(bytes).retrieve().toBodilessEntity();
        }catch(Exception ex){
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo almacenar la evidencia de pago",ex);
        }
        jdbc.update("""
          insert into app_private.comprobantes_pago
          (pedido_id,medio_pago_id,ruta_storage,mime,tamano_bytes)
          values(?,?,?,?,?)
          on conflict(pedido_id) do update set
            ruta_storage=excluded.ruta_storage,
            mime=excluded.mime,tamano_bytes=excluded.tamano_bytes,
            medio_pago_id=excluded.medio_pago_id,
            estado='PENDIENTE_CONFIRMACION',
            comentario_revision=null,revisado_por=null,revisado_en=null,
            creado_en=now()
          """,id,fila.get("medio_pago_id"),ruta,tipo,bytes.length);
        jdbc.update("""
          update public.pedidos set estado_pago='PENDIENTE_CONFIRMACION',
          expira_en=null,actualizado_en=now()
          where id=?
          """,id);
        jdbc.update("""
          insert into app_private.notificaciones_pago(pedido_id)
          values(?)
          """,id);
        jdbc.update("""
          insert into public.historial_pedido
            (pedido_id,estado_anterior,estado_nuevo,observacion)
          values(?,'PENDIENTE','PENDIENTE',
            'Comprobante recibido; pendiente de verificación bancaria')
          """,id);
        return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of(
          "pedidoId",id,
          "estadoPago","PENDIENTE_CONFIRMACION",
          "mensaje","Comprobante recibido. El administrador revisará el abono."));
    }

    private static String detectarMime(byte[] b) {
        if(b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255)
            return "image/jpeg";
        if(b.length>=8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71 &&
            b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10) return "image/png";
        if(b.length>=12 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F' &&
            b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P') return "image/webp";
        return null;
    }
}
