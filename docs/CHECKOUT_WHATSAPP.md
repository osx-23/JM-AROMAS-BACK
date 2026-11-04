# Checkout por WhatsApp — JM-AROMAS

Flujo: catálogo → carrito → dirección → **Coordinar por WhatsApp** → POST `/api/v1/checkout/pedidos` → pedido `PENDIENTE` con stock reservado durante 24 horas → WhatsApp con mensaje precargado → **el comprador pulsa Enviar**.

- La aplicación **no envía mensajes automáticamente** ni puede comprobar que el usuario pulsó Enviar.
- La configuración `CHECKOUT_ENABLED=false` mantiene cerrados Yape y transferencia.
- `CHECKOUT_WHATSAPP_ENABLED=true` permite únicamente pedidos de coordinación por WhatsApp; se puede apagar con `false` sin afectar a otros módulos.
- Solo se ofrece la modalidad WhatsApp si existe un medio `WHATSAPP` activo en la base.
- El servidor valida datos del cliente, consulta precios y descuentos reales, bloquea productos, reserva stock con transacción, almacena el pedido y devuelve líneas validadas.
- El frontend reutiliza un UUID de idempotencia por intención de compra; la restricción UNIQUE de PostgreSQL impide descontar stock dos veces por el mismo UUID.
- Protección básica en servidor: **máximo 2 pedidos por teléfono/hora y 5 por 24 horas** (teléfono normalizado), con bloqueo transaccional para peticiones simultáneas. Esta protección es inicial; para un lanzamiento público con tráfico relevante, agregar CAPTCHA/WAF por IP y monitorización.
- No adjuntar al mensaje de WhatsApp el token privado de seguimiento, ni datos completos de dirección o DNI.
- El cliente conserva un enlace de seguimiento privado y puede retomar el contacto si cerró WhatsApp.
- El administrador puede consultar `GET /api/v1/admin/pedidos-whatsapp` y actualizar `PATCH /api/v1/admin/pedidos-whatsapp/{id}/estado` con autenticación ADMIN. Estado CONTACTADO/CONFIRMADO/PREPARANDO evita vencimiento automático.
- Cancelación manual o expiración automática de un pedido `PENDIENTE` devuelve stock con movimientos auditables. Nunca equivale a pago confirmado.
- La entrega requiere confirmar pago de forma explícita; esta refactorización no crea una función de confirmación de pago por WhatsApp.

## Comprobación

1. Activar el método WHATSAPP en Administración → Métodos de pago.
2. Tener al menos un perfume publicado y con stock.
3. Con `CHECKOUT_WHATSAPP_ENABLED=true`, añadirlo al carrito y rellenar datos de entrega.
4. Terminar compra por WhatsApp; comprobar el código, importe del servidor, artículo y cantidad.
5. Comprobar en panel que el pedido está pendiente; luego marcar CONTACTADO.
6. No realizar pedidos de prueba innecesarios en producción: reservarán inventario.
