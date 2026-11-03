# JM-AROMAS: fotografías de productos

## Flujo para la administradora

1. Iniciar sesión con Google con rol `ADMIN`.
2. Abrir `/admin` y buscar el perfume por código de barras. Si no existe, registrar su nombre, marca, precio y stock.
3. Antes de guardar puedes elegir fotos del dispositivo o tomar una foto con la cámara del celular. Al guardar el producto se registrará como **borrador** y se subirán las fotos seleccionadas.
4. Para un producto ya registrado, pulsar **Gestionar fotos** en el inventario o escanear su código.
5. Seleccionar hasta **5 imágenes JPG, PNG o WEBP**, máximo **5 MB por imagen**.
6. Usar **Hacer principal** para elegir qué foto se ve primero en el catálogo; también se pueden eliminar fotografías.
7. Publicar el perfume desde el panel, tras verificar sus datos, stock e imágenes. Los clientes verán su foto principal en el catálogo y las demás en el detalle.

## Estructura

- **Tabla existente:** `public.imagenes_producto` (producto_id, ruta_storage, descripcion_alt, orden).
- **Bucket nuevo:** `jm-aromas-productos`, público para lectura de imágenes.
- **Rutas protegidas por AdminAuthFilter**:
  - `GET /api/v1/admin/productos/{id}/imagenes`: consultar galería.
  - `POST /api/v1/admin/productos/{id}/imagenes`: `multipart/form-data` con campo `archivo`.
  - `PATCH /api/v1/admin/productos/{id}/imagenes/{imagenId}/principal`: elegir foto portada.
  - `DELETE /api/v1/admin/productos/{id}/imagenes/{imagenId}`: eliminar imagen.
- **API pública:** `GET /api/v1/productos` y `GET /api/v1/productos/{slug}` devuelven `imagenes: string[]` con URLs públicas ordenadas.

## Requisitos de configuración

El backend necesita: `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` y **`SUPABASE_SERVICE_ROLE_KEY` (JWT service_role legado compatible con la integración REST actual)**.
La clave privilegiada **nunca** se almacena en el frontend ni se comparte por chat.
Las fotos de productos son públicas; los comprobantes de pago se mantienen en un **bucket privado distinto**.

La configuración del bucket está registrada en `database/fotos_productos.sql`; ya se aplicó al proyecto de Supabase JM-AROMAS.
Las restricciones se aplican tanto en el bucket como en la API: 5 MB, JPG/PNG/WEBP, inspección de bytes para identificar el formato, máximo 5 fotos por producto.

## Comprobación

- Usuario no autorizado: `POST /api/v1/admin/productos/{id}/imagenes` debe devolver 401/403.
- Admin: crear producto borrador, subir dos fotos, elegir la segunda como principal, publicar y consultar catálogo.
- Las imágenes deben mostrarse en el catálogo y la galería del detalle.
- Una imagen no válida o mayor de 5 MB se rechaza.
- Se puede eliminar una foto y la siguiente pasa a ser la principal.
- Al intentar una sexta foto se devuelve un error.
- Si falla Storage, la base de datos no debe recibir un registro de imagen nuevo.

Documentación de Supabase:
- https://supabase.com/docs/guides/storage/buckets/creating-buckets
- https://supabase.com/docs/guides/storage/buckets/fundamentals
