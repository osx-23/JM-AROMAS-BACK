package pe.jmaromas.api.checkout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Tarifas comerciales de delivery proporcionadas por JM Aromas.
 *
 * Delivery Lima: material entregado por la tienda; limite orientativo de
 * paquete 30x30x10 cm y 2 kg. Shalom: cargo COMERCIAL de JM Aromas,
 * S/ 8 por la primera unidad + S/ 2 por cada perfume adicional; NO es
 * una cotización emitida por Shalom.
 *
 * El precio de envío es SIEMPRE calculado del lado del servidor.
 */
@Service
public class EntregaService {
    public record Zona(String id, String grupo, String distrito,
                       String etiqueta, int precio, String departamento) {}
    public record Agencia(String id, String grupo, String nombre) {}
    public record Cotizacion(String tipo, String zonaId, String agenciaId,
                             BigDecimal costo, String descripcion) {}

    private final List<Zona> zonas = crearZonas();
    private final Map<String,Zona> zonasPorId = new LinkedHashMap<>();
    private final List<Agencia> agencias = leerAgencias();
    private final Map<String,Agencia> agenciasPorId = new LinkedHashMap<>();

    public EntregaService() {
        zonas.forEach(z -> zonasPorId.put(z.id(), z));
        agencias.forEach(a -> agenciasPorId.put(a.id(),a));
    }

    public List<Zona> zonasLima() { return zonas; }
    public List<Agencia> agenciasShalom() { return agencias; }

    public Cotizacion cotizar(String tipo, String zonaId, String agenciaId,
                              int unidades, String departamento, String distrito) {
        if (unidades < 1 || unidades > 600)
            throw new IllegalArgumentException("Cantidad de perfumes inválida para el envío");
        if ("DELIVERY_LIMA".equals(tipo)) {
            Zona zona = zonasPorId.get(zonaId);
            if (zona == null)
                throw new IllegalArgumentException("Selecciona una zona de delivery Lima");
            if (!normalizar(zona.departamento()).equals(normalizar(departamento)) ||
                !normalizar(zona.distrito()).equals(normalizar(distrito)))
                throw new IllegalArgumentException(
                    "El departamento y distrito deben coincidir con la zona de delivery seleccionada.");
            if (agenciaId != null && !agenciaId.isBlank())
                throw new IllegalArgumentException("El delivery Lima no utiliza agencias Shalom");
            return new Cotizacion(tipo, zona.id(), null,
                BigDecimal.valueOf(zona.precio()), "Delivery a domicilio: "+zona.etiqueta());
        }
        if ("SHALOM".equals(tipo)) {
            Agencia agencia = agenciasPorId.get(agenciaId);
            if (agencia == null)
                throw new IllegalArgumentException("Selecciona una agencia Shalom del directorio");
            if (zonaId != null && !zonaId.isBlank())
                throw new IllegalArgumentException("No selecciones zona Lima junto con Shalom");
            return new Cotizacion(tipo,null,agencia.id(),
                BigDecimal.valueOf(8L+2L*(unidades-1L)),
                "Recojo en Shalom: "+agencia.nombre());
        }
        throw new IllegalArgumentException("Selecciona DELIVERY_LIMA o SHALOM para la entrega");
    }

    static String normalizar(String valor) {
        if(valor==null) return "";
        return Normalizer.normalize(valor.strip(),Normalizer.Form.NFD)
            .replaceAll("\\p{M}","")
            .replaceAll("\\s+"," ")
            .toUpperCase(Locale.ROOT);
    }

    private static List<Zona> crearZonas() {
        List<Zona> z=new ArrayList<>();
        // Grupo | distrito | zona | precio
        String filas = """
            Centro|Breña||10
            Centro|Cercado de Lima||10
            Centro|Jesús María||10
            Centro|La Victoria||10
            Centro|Lince||10
            Centro|Magdalena del Mar||10
            Centro|Miraflores||10
            Centro|Pueblo Libre||10
            Centro|Rímac||10
            Centro|San Borja||10
            Centro|San Isidro||10
            Centro|San Miguel||10
            Centro|Santiago de Surco||10
            Centro|Surquillo||10
            Norte|Comas|Zona general|10
            Norte|Independencia|Zona general|10
            Norte|Los Olivos||10
            Norte|San Martín de Porres|Zona general|10
            Sur|Barranco||10
            Sur|Chorrillos||10
            Sur|San Juan de Miraflores||10
            Sur|Villa El Salvador||10
            Sur|Villa María del Triunfo|Zona general|10
            Este|Ate|Zona general|10
            Este|El Agustino||10
            Este|La Molina|Zona general|10
            Este|Santa Anita||10
            Este|San Juan de Lurigancho|Zona general|10
            Callao|Carmen de la Legua||10
            Callao|Callao|Zona general|10
            Callao|Bellavista||10
            Callao|La Perla||10
            Norte alejada|Carabayllo||16
            Norte alejada|Comas|Collique|16
            Norte alejada|Independencia|Payet Alto|16
            Norte alejada|Independencia|Tahuantinsuyo Alto|16
            Norte alejada|Puente Piedra||16
            Norte alejada|San Martín de Porres|Cerro Candela|16
            Norte alejada|San Martín de Porres|Cerro Chuquitanta|16
            Este alejada|Ate|Santa Clara|16
            Este alejada|Ate|Huaycán|16
            Este alejada|Ate|Huachipa|16
            Este alejada|San Juan de Lurigancho|Campoy|16
            Este alejada|San Juan de Lurigancho|Jicamarca|16
            Este alejada|La Molina|La Planicie|16
            Este alejada|La Molina|Musa|16
            Este alejada|La Molina|El Golf|16
            Este alejada|La Molina|Sol de la Molina|16
            Sur alejada|Villa María del Triunfo|José Gálvez|16
            Callao alejado|Callao|La Punta|16
            Callao alejado|Callao|Chucuito|16
            Callao alejado|Callao|Oquendo/Marquez|16
            Callao alejado|Ventanilla|Mi Perú|16
            Sur alejada|Lurín||20
            Callao alejado|Ventanilla|Pachacútec|20
            """;
        int i=0;
        for(String line:filas.split("\\R")) {
            if(line.isBlank())continue;
            String[] c=line.strip().split("\\|",-1);
            String nombre=c[1]+(c[2].isBlank() || "Zona general".equals(c[2])?"":" — "+c[2]);
            String dept=c[0].startsWith("Callao")?"Callao":"Lima";
            z.add(new Zona("LIMA_"+(++i),c[0],c[1],nombre,
                Integer.parseInt(c[3]),dept));
        }
        return List.copyOf(z);
    }

    private static List<Agencia> leerAgencias() {
        List<Agencia> lista = new ArrayList<>();
        ClassPathResource archivo = new ClassPathResource("shalom_agencias_referencia.txt");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(),StandardCharsets.UTF_8))) {
            String line;
            while ((line=r.readLine())!=null) {
                if(line.isBlank() || line.startsWith("#"))continue;
                String[] p=line.split("\\|",2);
                if(p.length!=2 || p[1].isBlank())continue;
                String id=p[0]+"|"+p[1];
                lista.add(new Agencia(id,p[0].equals("LIMA_CALLAO")
                    ?"Lima y Callao":"Otras regiones",p[1]));
            }
        } catch(Exception e) {
            throw new IllegalStateException("No se pudo cargar catálogo local de agencias Shalom",e);
        }
        return List.copyOf(lista);
    }
}
