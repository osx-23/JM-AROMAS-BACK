package pe.jmaromas.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.jmaromas.api.admin.service.DescuentosService;

/** Expone contratos HTTP; reglas de promociones y transacciones residen en DescuentosService. */
@RestController
@RequestMapping("/api/v1/admin/productos/{productoId}/descuentos")
public class DescuentosAdminController {
    private final DescuentosService service;
    public DescuentosAdminController(DescuentosService service) {this.service=service;}

    @GetMapping
    public List<Map<String,Object>> listar(@PathVariable UUID productoId) {
        return service.listar(productoId);
    }

    @PostMapping
    public ResponseEntity<?> crear(@PathVariable UUID productoId,
        @RequestBody DescuentosService.Formulario datos) {return service.crear(productoId,datos);}

    @PatchMapping("/{descuentoId}/desactivar")
    public ResponseEntity<Void> desactivar(@PathVariable UUID productoId,
        @PathVariable UUID descuentoId) {return service.desactivar(productoId,descuentoId);}
}
