package pe.jmaromas.api.catalogo;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Casos de uso del catálogo; permite añadir reglas sin acoplar HTTP y SQL. */
@Service
public class CatalogoService {
    private final CatalogoRepository repository;
    public CatalogoService(CatalogoRepository repository) {this.repository=repository;}
    public List<ProductoPublico> listar(int pagina,int tamano) {
        return repository.listar(pagina,tamano);
    }
    public Optional<ProductoPublico> detalle(String slug) {
        return repository.detalle(slug);
    }
}
