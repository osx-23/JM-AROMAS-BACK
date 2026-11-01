# Preparación de Supabase para JM-AROMAS

## Estado
- Se creó el **diseño SQL** en `database/schema.sql` de la rama `feature/mvp-base`.
- **NO se ha aplicado a ninguna base remota**: el proyecto `jm-aromas` todavía no figura entre los proyectos accesibles desde las conexiones de Supabase de este chat.
- No se borró ningún registro ni se creó una base nueva.
- Este script está pensado para un **proyecto sin estas tablas**; si ya existen tablas, revisar y adaptar antes de ejecutarlo.

## Qué construye
Tablas públicas: `perfiles`, `direcciones`, `categorias`, `productos`, `imagenes_producto`, `descuentos`, `zonas_entrega`, `pedidos`, `detalles_pedido`, `historial_pedido`, `movimientos_stock`.

Tablas privadas: `app_private.administradores` y `app_private.configuracion`.

Todas las tablas del esquema `public` tienen RLS habilitado. El catálogo publicado es visible al público. Un usuario autenticado puede gestionar solo su perfil y sus direcciones y consultar solo sus pedidos. **Los pedidos, cambios de precios, descuentos, inventario y privilegios de administrador solo se escriben desde un backend confiable.**

## Acceso pendiente
1. Abrir Supabase y copiar la URL del proyecto **jm-aromas**, como `https://supabase.com/dashboard/project/REFERENCIA`.
2. Comprobar que el proyecto figure en la conexión Supabase autorizada para este chat.
3. Listar tablas existentes, extensiones y políticas de seguridad.
4. Aplicar el SQL tras verificar compatibilidad y realizar consultas de validación.
5. Configurar un bucket de Storage para fotografías de productos, con permisos de escritura restringidos.

**Nunca compartir contraseñas de base de datos, claves de servicio ni secretos de Google en el chat o GitHub.**

## Autenticación con Google
- Usar **Supabase Auth**, habilitar Google en Authentication > Providers.
- Configurar el cliente OAuth en Google Cloud con los orígenes y callbacks proporcionados por Supabase.
- El frontend empleará exclusivamente la clave **publishable**, nunca la `service_role` ni secretos.
- El backend debe **verificar firma, emisor, audiencia y expiración** del JWT antes de ejecutar operaciones autenticadas.
- No guardar un indicador `admin` editable por el usuario. La tabla privada `app_private.administradores` requiere que el administrador sea añadido por una cuenta de confianza.

Referencia: https://supabase.com/docs/guides/auth/social-sign-in/auth-google

## Flujo de pedido
1. Cliente inicia sesión y guarda dirección de entrega.
2. El backend consulta productos activos, recalcula descuentos y total y valida stock.
3. Crea el pedido y sus detalles **dentro de una transacción**, usando `idempotencia` para evitar duplicados.
4. Devuelve código y resumen al frontend.
5. El frontend abre un enlace `wa.me` con mensaje prellenado; **el comprador pulsa Enviar**.
6. La administradora confirma disponibilidad, entrega y pago. Un pedido pendiente no es una venta cobrada.
7. El descuento del stock debe quedar auditado con `movimientos_stock`; reservar/descontar/reponer se diseñará con transacciones y bloqueo de fila.

El número WhatsApp, zonas y tarifas no se han definido: no fijar valores ficticios.

## Facturación peruana
La emisión de boletas y facturas electrónicas será una fase posterior y se integrará con **SUNAT**, no SUNARP. Antes de desarrollarla, confirmar RUC, modalidad de emisión y requisitos tributarios.

## Comprobaciones antes de publicar
- Comprobar RLS y privilegios por rol (`anon`, `authenticated`).
- Probar que ningún cliente vea direcciones o pedidos ajenos.
- Probar que el navegador no pueda alterar stock ni autoasignarse administrador.
- Probar transacciones, idempotencia y los límites de descuentos.
- Revisar Security/Performance Advisors de Supabase.
