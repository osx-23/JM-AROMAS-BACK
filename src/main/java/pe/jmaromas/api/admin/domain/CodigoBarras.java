package pe.jmaromas.api.admin.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalización del código del fabricante fuera del controlador y sin dependencias de Spring.
 * Acepta EAN-8, EAN-13, GTIN-14, UPC-A (a EAN-13) y códigos internos alfanuméricos.
 */
public final class CodigoBarras {
    private static final Pattern CODIGO = Pattern.compile("[A-Z0-9._-]{4,64}");
    private static final Pattern SOLO_DIGITOS = Pattern.compile("[0-9]+");
    private CodigoBarras() {}

    public static String normalizar(String texto) {
        if(texto==null)throw new IllegalArgumentException("Código requerido");
        String codigo=texto.strip().toUpperCase(Locale.ROOT);
        if(SOLO_DIGITOS.matcher(codigo).matches() && codigo.length()==12)
            codigo="0"+codigo; // UPC-A representado como EAN-13.
        if(!CODIGO.matcher(codigo).matches())
            throw new IllegalArgumentException("Formato no admitido");
        if(SOLO_DIGITOS.matcher(codigo).matches()) {
            if(!(codigo.length()==8 || codigo.length()==13 || codigo.length()==14)
               || !checksumGtin(codigo))
                throw new IllegalArgumentException("GTIN sin dígito verificador válido");
        }
        return codigo;
    }

    private static boolean checksumGtin(String value) {
        int suma=0;
        for(int i=value.length()-2,p=0;i>=0;i--,p++)
            suma+=(value.charAt(i)-'0')*(p%2==0?3:1);
        return (10-(suma%10))%10==value.charAt(value.length()-1)-'0';
    }
}
