package pe.jmaromas.api.admin.dashboard;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardServiceTest {
    @Test
    void rechazaPeriodosFueraDeLaListaPermitida() {
        var repo=mock(DashboardRepository.class);
        var service=new DashboardService(repo);
        assertThrows(IllegalArgumentException.class,()->service.resumen(0));
        assertThrows(IllegalArgumentException.class,()->service.resumen(365));
        verifyNoInteractions(repo);
    }

    @Test
    void permiteFiltrosControladosSinConsultasArbitrarias() {
        var repo=mock(DashboardRepository.class);
        var service=new DashboardService(repo);
        when(repo.resumen(30)).thenReturn(Map.of("periodoDias",30));
        assertEquals(30,service.resumen(30).get("periodoDias"));
        verify(repo).resumen(30);
    }
}
