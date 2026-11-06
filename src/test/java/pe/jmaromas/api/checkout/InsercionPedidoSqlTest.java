package pe.jmaromas.api.checkout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InsercionPedidoSqlTest {
    @Test
    void asignaTipoAReferenciaOpcional() {
        String sql = CheckoutPublicController.INSERTAR_PEDIDO_SQL;
        assertTrue(sql.contains("'referencia',cast(? as text)"));
        assertEquals(27, sql.split("\\?", -1).length - 1);
    }
}
