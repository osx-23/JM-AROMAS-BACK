# JM-AROMAS — Yape y transferencia (verificación manual)

## Activación

- `CHECKOUT_ENABLED=false` puede permanecer igual: es el interruptor legado.
- `CHECKOUT_MANUAL_ENABLED=true` (valor por defecto) permite **pedidos con Yape o transferencia**, sin pasarela de pago.
- `CHECKOUT_WHATSAPP_ENABLED=true` gestiona de forma independiente los pedidos por WhatsApp.
- `CHECKOUT_MANUAL_ENABLED=false` deshabilita los medios manuales si `CHECKOUT_ENABLED` permanece en false.

**Importante:** en la base de datos JM-AROMAS todavía no existen cuentas registradas de Yape o transferencia. Se deben crear mediante el panel; nunca generar cuentas o QR de ejemplo para un pago real.

## Configurar cuentas en el panel

En `/admin` → **Configuración de cobros** → **Añadir medio de pago**.

**Yape:** indicar título, titular, número de Yape real y subir la imagen correcta del QR. Activar el método solo tras comprobar los datos. La carga del QR se hace en `jm-aromas-medios-pago`, bucket de lectura pública para que el comprador pueda escanearlo.

**Transferencia:** indicar título, nombre del banco, titular, número de cuenta y (si se desea) CCI. Activar después de verificar que los datos corresponden a la tienda.

Solo se listan a compradores los medios **activos y completos**. El servidor vuelve a comprobar el medio elegido antes de crear el pedido.

## Flujo del comprador

1. Agregar al carrito un perfume publicado y disponible.
2. Completar nombre, DNI, teléfono, correo y domicilio.
3. Seleccionar **Yape o transferencia**; elegir la cuenta publicada por la tienda.
4. Pulsar **Crear pedido y continuar**. El backend valida precios y descuentos, registra el pedido con estado `PENDIENTE` y reserva inventario durante 24 h.
5. Efectuar el abono por la aplicación de Yape o banco directamente, a la cuenta real mostrada.
6. Subir el comprobante JPG/PNG/WEBP (máximo 5 MB), que se guarda en el bucket **privado** `jm-aromas-comprobantes`.
7. El estado pasa a `PENDIENTE_CONFIRMACION` y la administradora recibe la tarea en **Comprobantes pendientes**. El pedido no se marca pagado automáticamente.
8. La administradora consulta sus movimientos reales y **confirma o rechaza** el comprobante. Confirmar lo marca `PAGADO` / `CONFIRMADO`; rechazar devuelve el estado `RECHAZADO` para que el cliente pueda enviar otra evidencia.
9. El cliente puede abrir su enlace privado de seguimiento para volver a ver los datos de la cuenta y enviar otro comprobante cuando esté permitido.

Si la reserva vence sin evidencia aceptada, `ReservasVencidasJob` anula el pedido y devuelve el inventario. Mientras un comprobante está pendiente de revisión, la reserva no se cancela automáticamente.

## Protecciones

- Las transacciones y reservas se calculan en Spring Boot, nunca a partir del importe enviado por el navegador.
- UUID de idempotencia para evitar duplicar pedidos.
- Límite inicial compartido por número de teléfono: 2 pedidos/hora y 5 pedidos/24 h; afecta a WhatsApp, Yape y transferencia.
- Comprobantes privados; se descargan exclusivamente desde el backend autenticado con ADMIN.
- La recepción de una imagen no equivale a recibir dinero. La decisión de pago está protegida por el rol ADMIN.
- `SUPABASE_SERVICE_ROLE_KEY` solo en servidor y `.env.local`, nunca en Angular ni en GitHub.
- Para publicar la tienda al público: agregar CAPTCHA/WAF, monitorización del abuso y pruebas reales de recibo de pago; el límite por teléfono no evita todos los ataques.

## Validación antes de usar en ventas

- Probar ambos métodos con una compra de importe pequeño, revisando la recepción real del dinero (no usar capturas falsas para aprobar pagos).
- Verificar que los métodos inactivos no aparecen en `GET /api/v1/checkout/medios-pago`.
- Confirmar que un pedido aparece en el panel con evidencia privada y que un usuario sin rol ADMIN no puede verla.
- Rechazar un comprobante y probar el reenvío mediante seguimiento; comprobar que el pedido conserva idempotencia.
- Confirmar el pago solo después de verificar el dinero y revisar el cambio a `PAGADO`.
- Revisar el email de notificaciones si SMTP está configurado; que no haya email no implica fallo del pedido.
