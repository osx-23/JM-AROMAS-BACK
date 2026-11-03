package pe.jmaromas.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import pe.jmaromas.api.admin.service.InventarioService;

/** Capa HTTP: delega validación, transacciones y consultas al servicio. */
@RestController
@RequestMapping("/api/v1/admin/productos")
public class InventarioController {
    private final InventarioService service;
    public InventarioController(InventarioService service) {this.service=service;}

    @GetMapping
    public List<Map<String,Object>> listar() {return service.listar();}

    @GetMapping("/codigo-barras/{codigo}")
    public ResponseEntity<Map<String,Object>> buscar(@PathVariable String codigo) {
        return service.buscar(codigo);
    }

    @PostMapping
    public ResponseEntity<Map<String,Object>> registrar(
        @RequestBody InventarioService.Registro data,HttpServletRequest request) {
        return service.registrar(data,request);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String,Object>> editar(@PathVariable UUID id,
        @RequestBody InventarioService.Edicion data) {return service.editar(id,data);}

    @PatchMapping("/{id}/publicacion")
    public ResponseEntity<Map<String,Object>> publicar(@PathVariable UUID id,
        @RequestBody InventarioService.Publicacion body) {return service.publicar(id,body);}

    @PatchMapping("/{id}/stock")
    public ResponseEntity<Map<String,Object>> ingresar(@PathVariable UUID id,
        @RequestBody InventarioService.IngresoStock body,HttpServletRequest request) {
        return service.ingresar(id,body,request);
    }
}
