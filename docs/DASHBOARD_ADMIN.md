# API de dashboard administrativo (JM-AROMAS)

## Ruta protegida

`GET /api/v1/admin/dashboard/resumen?dias=30`

- Parámetro `dias`: se admiten solo **7**, **30** o **90**.
- Valida JWT de Supabase y rol ADMIN en `AdminAuthFilter`.
- Respuesta JSON: `periodoDias`, `fechaInicio`, `fechaFin`, `zonaHoraria`, `pedidos`, `inventario`, `ventasDiarias`, `metodos`, `masVendidos`, `stockCritico`, `pedidosRecientes`.
- No devuelve correo, nombre, teléfono, DNI, dirección ni token de ningún cliente.
- Consultas SQL parametrizadas, `@Transactional(readOnly=true)`.

## Reglas de métricas

- Los ingresos suman exclusivamente `public.pedidos` con `estado_pago='PAGADO'`.
- Período de cobro: `coalesce(confirmacion_pago_en,creado_en)` y fecha en zona `America/Lima`.
- Los pedidos recibidos y pendientes se filtran por **fecha de creación**; no son equivalentes a ventas.
- Los productos más vendidos provienen de líneas de pedidos **pagados**.
- El stock y los últimos ocho pedidos muestran estado **actual/reciente**, sin depender del período seleccionado.
- Los pedidos pendientes de verificación jamás se cuentan como ventas cobradas.

## Arquitectura

```text
admin/dashboard/
  DashboardController.java    -> HTTP /api/v1/admin/dashboard
  DashboardService.java       -> período validado, transacción de lectura
  DashboardRepository.java    -> SQL de métricas e inventario
```

Los informes de Google Analytics 4 son una fuente **separada**: la recopilación de tráfico vive en el frontend con consentimiento y el panel enlaza a Analytics. Para consultar sus métricas desde este backend se necesitará un **Property ID numérico y credenciales de Analytics Data API**, aún no configurados.

Ejecuta `mvn test`; las consultas se pueden contrastar con los datos de Supabase y con el historial de confirmaciones de pago.
