package pe.jmaromas.api.admin.dashboard;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint administrativo verificado por AdminAuthFilter (Supabase + tabla privada).
 * No publica estadísticas ni información de clientes a usuarios normales.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) {this.service=service;}

    @GetMapping("/resumen")
    public Map<String,Object> resumen(@RequestParam(defaultValue="30") int dias) {
        return service.resumen(dias);
    }
}
