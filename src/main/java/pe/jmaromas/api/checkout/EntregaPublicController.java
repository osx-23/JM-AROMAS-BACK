package pe.jmaromas.api.checkout;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tarifas y agencias de consulta pública; la cotización definitiva se revalida en el pedido. */
@RestController
@RequestMapping("/api/v1/checkout/entrega")
public class EntregaPublicController {
    private final EntregaService servicio;
    public EntregaPublicController(EntregaService servicio) {
        this.servicio=servicio;
    }

    @GetMapping("/opciones")
    public Opciones opciones() {
        return new Opciones(servicio.zonasLima(),servicio.agenciasShalom(),
            "https://shalom.com.pe/agencias",
            "El listado Shalom es una referencia publicada por la empresa; confirma que la sede sigue atendiendo.",
            "Shalom: precio comercial de JM Aromas S/ 8 por primer perfume, S/ 2 por unidad adicional.");
    }

    public record Opciones(List<EntregaService.Zona> zonasLima,
                           List<EntregaService.Agencia> agenciasShalom,
                           String directorioOficialShalom,
                           String advertenciaAgencias, String notaTarifaShalom) {}
}
