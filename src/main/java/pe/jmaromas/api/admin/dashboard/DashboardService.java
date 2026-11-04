package pe.jmaromas.api.admin.dashboard;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Estadísticas contables: solo los pedidos con pago PAGADO suman ingresos. */
@Service
public class DashboardService {
    private final DashboardRepository repository;
    public DashboardService(DashboardRepository repository) {this.repository=repository;}

    @Transactional(readOnly=true)
    public Map<String,Object> resumen(int dias) {
        if(dias!=7 && dias!=30 && dias!=90)
            throw new IllegalArgumentException("Selecciona un período de 7, 30 o 90 días");
        return repository.resumen(dias);
    }
}
