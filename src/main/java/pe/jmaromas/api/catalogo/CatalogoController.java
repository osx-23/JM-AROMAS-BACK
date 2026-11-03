package pe.jmaromas.api.catalogo;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** API pública; sin lógica SQL ni credenciales de almacenamiento. */
@RestController
@RequestMapping("/api/v1/productos")
public class CatalogoController {
    private final CatalogoService service;
    public CatalogoController(CatalogoService service) {this.service=service;}

    @GetMapping
    public List<ProductoPublico> listar(@RequestParam(defaultValue="0") int pagina,
                                         @RequestParam(defaultValue="12") int tamano) {
        return service.listar(pagina,tamano);
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ProductoPublico> detalle(@PathVariable String slug) {
        return service.detalle(slug).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
