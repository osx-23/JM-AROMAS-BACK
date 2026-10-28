# JM-AROMAS — Backend

API para una tienda virtual de perfumes y productos afines con administración de pedidos, inventario, descuentos y ventas.

> Estado: planificación / backlog inicial. La arquitectura y la base de datos todavía deben implementarse.

## Alcance MVP
- Autenticación mediante Google y autorización de roles `CLIENTE` y `ADMIN` desde el servidor.
- Productos, categorías, fotos, precios, SKU y códigos de barras.
- Inventario, movimientos y auditoría.
- Promociones y cálculo seguro de descuentos vigentes.
- Perfil del cliente, teléfono y varias direcciones de entrega.
- Creación de pedidos sin pasarela de pagos ni cobro digital.
- Opciones de pago: `CONTRA_ENTREGA` y `A_COORDINAR`.
- Estados: `PENDIENTE`, `CONTACTADO`, `CONFIRMADO`, `EN_PREPARACION`, `ENTREGADO`, `CANCELADO`.
- Enlace de WhatsApp con número del negocio configurado y texto prellenado; nunca suponer que el mensaje fue enviado.
- Seguridad de datos personales, trazabilidad y validaciones.

## Fase posterior
- Reportes y gráficos de **ventas confirmadas**, diferenciados de pedidos pendientes.
- Emisión electrónica de boletas y facturas por **SUNAT** (no SUNARP). La integración exige confirmar RUC, modalidad SEE y requisitos de emisión/validación antes del desarrollo.

## Modelo lógico propuesto (por validar)
`usuarios`, `direcciones`, `categorias`, `productos`, `imagenes_producto`, `inventario_movimientos`, `descuentos`, `pedidos`, `pedido_detalles`, `pedido_estados`, `configuracion_negocio`, `comprobantes`.

No se considera definitiva esta lista: se validará durante el diseño de base de datos y reglas de negocio.

## Reglas críticas
- Un pedido `PENDIENTE` no es una venta cobrada.
- Precio y descuento se recalculan en backend antes de crear el pedido.
- La administración es de acceso restringido por rol; la interfaz por sí sola no basta.
- La política de reserva/descuento de stock al confirmar o cancelar requiere diseño transaccional.
- La emisión tributaria real y su aceptación deben registrarse; un PDF por sí solo no es una factura electrónica válida.
- Direcciones, números, claves y credenciales se protegen: no exponer datos ni secretos.

## Backlog inicial
[Ver Issues del backend](https://github.com/osx-23/JM-AROMAS-BACK/issues) · [Frontend](https://github.com/osx-23/JM-AROMAS-FRONT).

## Decisiones pendientes
- Stack del servidor, base de datos y alojamiento.
- RUC y régimen tributario del negocio; modalidad de emisión SUNAT.
- Datos fiscales, costo de envío, zonas de reparto y número WhatsApp.
- Políticas de datos personales, confirmación de pagos y control de inventario.
