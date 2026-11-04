package pe.jmaromas.api.config;

import java.util.Map;
import pe.jmaromas.api.checkout.CheckoutPublicController;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Errores de validación en formato estable para Angular.
 * Nunca exponer stacktraces, SQL, cabeceras de autorización ni credenciales.
 */
@RestControllerAdvice(assignableTypes = CheckoutPublicController.class)
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> argumentoInvalido(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", error.getMessage()==null
                ? "La solicitud contiene datos inválidos"
                : error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> solicitudIlegible(HttpMessageNotReadableException error) {
        // Un UUID o un campo JSON con formato inválido produce también un HTTP 400.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error",
                "La solicitud tiene un formato inválido. Actualiza la página y revisa los datos del pedido."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> estadoExcepcion(ResponseStatusException error) {
        int code=error.getStatusCode().value();
        String message=code>=500 ? "Error temporal del servidor. Intenta más tarde."
            : (error.getReason()==null ? "No se pudo procesar la solicitud" : error.getReason());
        return ResponseEntity.status(error.getStatusCode())
            .body(Map.of("error", message));
    }
}
