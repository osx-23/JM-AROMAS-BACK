package pe.jmaromas.api.admin;

import java.io.IOException;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/medios-pago")
public class GestionMediosPagoController {
    private final JdbcTemplate jdbc;
    private final RestClient storage;
    private final String serviceRoleKey;
    private final String supabaseUrl;
    public GestionMediosPagoController(JdbcTemplate jdbc,
        @Value("${app.supabase.url:https://gonhxfvelgxnjqpdyzpg.supabase.co}") String supabaseUrl,
        @Value("${app.supabase.service-role-key:}") String serviceRoleKey) {
        this.jdbc=jdbc;this.supabaseUrl=supabaseUrl;this.serviceRoleKey=serviceRoleKey;
        this.storage=RestClient.builder().baseUrl(supabaseUrl).build();
    }
    public record MedioForm(String tipo,String titulo,String titular,
        String numeroYape,String qrUrl,String banco,String numeroCuenta,
        String cci,String telefonoWhatsapp,Boolean activo,Integer orden) {}

    @GetMapping
    public List<Map<String,Object>> listar(){
        return jdbc.queryForList("""
          select id,tipo,titulo,titular,numero_yape as "numeroYape",
          qr_url as "qrUrl",banco,numero_cuenta as "numeroCuenta",
          cci,telefono_whatsapp as "telefonoWhatsapp",activo,orden
          from app_private.medios_pago order by tipo,orden,creado_en
          """);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String,Object>> crear(@RequestBody MedioForm form){
        validar(form);
        UUID id=UUID.randomUUID();
        jdbc.update("""
           insert into app_private.medios_pago
           (id,tipo,titulo,titular,numero_yape,qr_url,banco,numero_cuenta,cci,
            telefono_whatsapp,activo,orden)
           values(?,?,?,?,?,?,?,?,?,?,?,?)
           """,id,form.tipo(),form.titulo().strip(),trim(form.titular()),
           trim(form.numeroYape()),trim(form.qrUrl()),trim(form.banco()),
           trim(form.numeroCuenta()),trim(form.cci()),trim(form.telefonoWhatsapp()),
           Boolean.TRUE.equals(form.activo()),form.orden()==null?0:form.orden());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> actualizar(@PathVariable UUID id,@RequestBody MedioForm form) {
        validar(form);
        int n=jdbc.update("""
            update app_private.medios_pago
            set tipo=?,titulo=?,titular=?,numero_yape=?,qr_url=?,banco=?,
                numero_cuenta=?,cci=?,telefono_whatsapp=?,activo=?,orden=?,
                actualizado_en=now()
            where id=?
            """,form.tipo(),form.titulo().strip(),trim(form.titular()),
            trim(form.numeroYape()),trim(form.qrUrl()),trim(form.banco()),
            trim(form.numeroCuenta()),trim(form.cci()),trim(form.telefonoWhatsapp()),
            Boolean.TRUE.equals(form.activo()),form.orden()==null?0:form.orden(),id);
        return n==1?ResponseEntity.noContent().build():ResponseEntity.notFound().build();
    }

    @PostMapping(path="/{id}/qr",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String,String> subirQr(@PathVariable UUID id,
        @RequestParam("archivo") MultipartFile archivo) throws IOException {
        if(serviceRoleKey.isBlank()) throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Credencial privada Storage no configurada");
        List<String> medios=jdbc.query("""
          select tipo from app_private.medios_pago where id=? for update
          """,(rs,n)->rs.getString(1),id);
        if(medios.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Medio inexistente");
        if(!"YAPE".equals(medios.getFirst()))
            throw new IllegalArgumentException("Solo se sube QR a medios Yape");
        if(archivo.isEmpty()||archivo.getSize()>3*1024*1024)
            throw new IllegalArgumentException("QR máximo 3 MB");
        byte[] contenido=archivo.getBytes();
        String ext;
        String mime;
        if(contenido.length>=8 && (contenido[0]&255)==137 && contenido[1]==80 &&
            contenido[2]==78 && contenido[3]==71 && contenido[4]==13 &&
            contenido[5]==10 && contenido[6]==26 && contenido[7]==10){
            ext=".png";mime="image/png";
        }else if(contenido.length>=3 && (contenido[0]&255)==255 && (contenido[1]&255)==216 &&
                (contenido[2]&255)==255){
            ext=".jpg";mime="image/jpeg";
        }else if(contenido.length>=12 && contenido[0]=='R' && contenido[1]=='I' &&
                contenido[2]=='F' && contenido[3]=='F' && contenido[8]=='W' &&
                contenido[9]=='E' && contenido[10]=='B' && contenido[11]=='P'){
            ext=".webp";mime="image/webp";
        }else throw new IllegalArgumentException("Solo JPG, PNG o WEBP");
        String ruta="qr/"+UUID.randomUUID()+ext;
        try{
            storage.post().uri("/storage/v1/object/jm-aromas-medios-pago/"+ruta)
                .header("apikey",serviceRoleKey)
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceRoleKey)
                .contentType(MediaType.parseMediaType(mime))
                .body(contenido).retrieve().toBodilessEntity();
        }catch(Exception e){
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"No se pudo subir QR",e);
        }
        String url=supabaseUrl+"/storage/v1/object/public/jm-aromas-medios-pago/"+ruta;
        jdbc.update("update app_private.medios_pago set qr_url=?,actualizado_en=now() where id=?",
            url,id);
        return Map.of("qrUrl",url);
    }

    private void validar(MedioForm f){
        if(f==null||!List.of("YAPE","TRANSFERENCIA","WHATSAPP").contains(f.tipo())||
            f.titulo()==null||f.titulo().strip().length()<3||f.titulo().length()>120)
            throw new IllegalArgumentException("Tipo o título inválido");
        if(f.orden()!=null && Math.abs((long)f.orden())>100000)
            throw new IllegalArgumentException("Orden fuera de rango");
        if(Boolean.TRUE.equals(f.activo())){
            if(f.tipo().equals("YAPE") &&
               (trim(f.titular())==null || trim(f.numeroYape())==null || trim(f.qrUrl())==null))
                throw new IllegalArgumentException("Falta titular, número o QR de Yape");
            if(f.tipo().equals("TRANSFERENCIA") &&
               (trim(f.titular())==null || trim(f.banco())==null || trim(f.numeroCuenta())==null))
                throw new IllegalArgumentException("Falta banco, titular o número de cuenta");
            if(f.tipo().equals("WHATSAPP") &&
               (trim(f.telefonoWhatsapp())==null ||
               !trim(f.telefonoWhatsapp()).matches("\\+?[0-9]{9,15}")))
                throw new IllegalArgumentException("WhatsApp debe incluir número con código de país");
        }
    }
    private String trim(String s){return s==null||s.isBlank()?null:s.strip();}
}
