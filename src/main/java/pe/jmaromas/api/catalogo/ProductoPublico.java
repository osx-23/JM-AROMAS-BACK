package pe.jmaromas.api.catalogo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProductoPublico(
        UUID id,
        String nombre,
        String slug,
        String marca,
        String descripcion,
        BigDecimal precio,
        boolean disponible,
        String categoria,
        List<String> imagenes,
        BigDecimal precioAnterior,
        String publicoObjetivo,
        Integer contenidoMl
) {}
