package pe.jmaromas.api.admin.dashboard;

import java.util.Map;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Estadísticas contables: solo los pedidos con pago PAGADO suman ingresos. */
@Service
public class DashboardService {
    private final DashboardRepository repository;
    public DashboardService(DashboardRepository repository) {this.repository=repository;}

    @Transactional(readOnly=true)
    public Map<String,Object> resumen(Integer dias,LocalDate desde,LocalDate hasta) {
        LocalDate hoy=LocalDate.now(ZoneId.of("America/Lima"));
        if(dias!=null) {
            if(dias!=7 && dias!=30 && dias!=90)
                throw new IllegalArgumentException("Selecciona un período de 7, 30 o 90 días");
            return repository.resumen(hoy.minusDays(dias-1),hoy);
        }
        if(desde==null && hasta==null) {
            YearMonth mes=YearMonth.from(hoy);
            return repository.resumen(mes.atDay(1),mes.atEndOfMonth());
        }
        if(desde==null || hasta==null || desde.isAfter(hasta) ||
           ChronoUnit.DAYS.between(desde,hasta)>366 ||
           desde.isBefore(LocalDate.of(2000,1,1)) || hasta.isAfter(hoy.plusYears(1)))
            throw new IllegalArgumentException("Selecciona un rango válido de hasta 367 días");
        return repository.resumen(desde,hasta);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> resumen(int dias) {
        if(dias!=7 && dias!=30 && dias!=90)
            throw new IllegalArgumentException("Selecciona un período de 7, 30 o 90 días");
        return repository.resumen(dias);
    }
}
