# JM-AROMAS — Pago manual, pedidos con invitado y confirmación administrativa

## Lo implementado en `feature/mvp-base`

- **Tienda pública** (Angular): carrito de productos reales, ruta `/checkout`.
- **Dos caminos** en checkout: `COORDINAR_WHATSAPP`, o web con `YAPE` / `TRANSFERENCIA`.
- **Invitado o Google**: en ambos casos se solicitan nombre, DNI peruano (8 dígitos), celular, email y dirección; usuario de Google puede elegir guardar su perfil y dirección.
- **Pedido transaccional**: el backend reconsulta precio y disponibilidad, calcula el total de productos, crea código JMA y reserva las unidades; reserva vence a las 24 horas si no llega evidencia. Los precios enviados por navegador nunca se consideran autorizados.
- **Pago manual**: comprobante JPG/PNG/WEBP (hasta 5 MB) en bucket privado `jm-aromas-comprobantes`; al subirlo el pedido pasa a `PENDIENTE_CONFIRMACION` y se genera un aviso para correo.
- **Panel administrador**: bandeja de comprobantes, lectura privada de captura, datos de la cuenta receptora; confirmar manualmente (estado `PAGADO`) o rechazar (estado `RECHAZADO`).
- **Correo**: bandeja persistente `app_private.notificaciones_pago`; envío mediante Resend si se configura API key, remitente y correo destino. Sin credenciales, el aviso queda pendiente y **no se envía**.
- **Seguimiento**: ruta `/pedido/:id#token=...` (fragmento secreto); lectura por token de alta entropía. Los usuarios invitados deben conservar el enlace privado.
- **Administración de medios de pago**: añadir Yape, varios bancos o WhatsApp en el panel, editarlos, subir QR de Yape (bucket separado público) y activarlos solo cuando estén completos.
- **Reserva expirada**: tarea programada cancela órdenes sin evidencia al vencer plazo y devuelve stock de forma transaccional con movimientos auditables.

## Lo que NO está configurado aún

No hay números de Yape, cuentas bancarias, QR ni teléfono de WhatsApp registrados. Tampoco hay productos publicados ni administradores autorizados. Por tanto, **la compra real debe permanecer inhabilitada hasta configurarlo**.

Nadie debe adivinar o inventar cuentas bancarias, monto de delivery, correo del administrador o imagen QR.

## Configurar local

1. Actualizar ambos repositorios `feature/mvp-base`, en frontend `npm install && npm start`, backend `mvn spring-boot:run`.
2. Backend: `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `FRONTEND_ORIGIN`.
3. Para upload privado y QR: definir `SUPABASE_SERVICE_ROLE_KEY` **solo en el backend**. Esta variable espera la clave heredada `service_role` (JWT) de Supabase; **nunca** colocarla en GitHub o Angular. Puede gestionarse en variables seguras de Railway.
4. Para aviso por correo: `RESEND_API_KEY`, `EMAIL_FROM_ADDRESS` (remitente verificado), `ADMIN_NOTIFICATION_EMAIL` y `APP_PUBLIC_URL`.
5. Configurar Google OAuth en Supabase; permitir `http://localhost:4200`, `http://localhost:4200/admin` y `http://localhost:4200/checkout` en URLs de redirección.
6. Autorizar la primera administradora desde SQL Editor (no desde el navegador):
   ```sql
   insert into app_private.administradores(usuario_id)
   values ('UUID-EXISTENTE-DE-AUTH-USERS')
   on conflict(usuario_id) do nothing;
   ```
7. Abrir `/admin` con Google, al final del panel ir a **Yape, bancos y WhatsApp**: crear medio inactivo, registrar datos verificados, subir QR si es Yape, activar y guardar.
8. Publicar al menos un producto real (el registro por escáner crea borradores). No se insertaron productos ficticios en Supabase.
9. Ir a `/checkout` desde el carrito, crear un pedido como invitado o con Google, subir evidencia, observar el estado en `/pedido/:id`.
10. Como admin, revisar el comprobante y **verificar por cuenta propia el ingreso en Yape/banco**, luego confirmar. El cliente verá el cambio al actualizar la pantalla de estado.

## Seguridad y reglas importantes

- La captura enviada NO demuestra que el dinero llegó. Nunca confirmar automáticamente por OCR o imagen.
- El importe online es **solo productos**; costo del envío aún debe coordinarse y no está incluido en el cobro. No presentarlo como envío gratuito.
- Las cuentas y QR se publican para el comprador solo cuando los medios están activos. El comprobante sigue siendo privado.
- La API valida rol administrador mediante `app_private.administradores`, no mediante metadatos editables del usuario.
- El backend es quien crea y confirma pedidos. Un cliente no puede escribir directo el estado del pedido en Supabase.
- El token del pedido es secreto. No compartir su enlace público con terceros. El token se almacena en BD como SHA-256.
- El checkout requiere **endurecimiento anti-abuso** (rate limit, CAPTCHA/Turnstile, verificación de correo invitado) y una política de tratamiento de DNI, datos personales y evidencias antes de salir a producción.
- Con varias instancias desplegadas, validar idempotencia, expiraciones, reintentos y flujo de pago en pruebas de integración, no solo en compilación.
- El panel de administración no reemplaza ni consulta automáticamente la cuenta de banco o Yape.

## APIs nuevas

Públicas:
- `GET /api/v1/checkout/medios-pago`
- `POST /api/v1/checkout/pedidos`
- `POST /api/v1/checkout/pedidos/{id}/comprobante` (multipart, header `X-Pedido-Token`)
- `GET /api/v1/checkout/pedidos/{id}` (header `X-Pedido-Token`)

Administración (Bearer JWT + rol en tabla privada):
- `GET /api/v1/admin/pedidos/pendientes`
- `GET /api/v1/admin/pedidos/{id}/comprobante`
- `PATCH /api/v1/admin/pedidos/{id}/pago`
- `GET /api/v1/admin/medios-pago`
- `POST /api/v1/admin/medios-pago`
- `PATCH /api/v1/admin/medios-pago/{id}`
- `POST /api/v1/admin/medios-pago/{id}/qr`

## Limitaciones actuales

- Sin pasarela, webhooks de cobro, pago automático ni conciliación bancaria.
- Sin facturación electrónica ni cálculo de envío automático.
- Sin correo de confirmación al comprador; solo aviso configurable al administrador.
- Sin pruebas end-to-end con fondos reales ni despliegue público HTTPS.
- Las tarjetas demo no generan pedidos reales.

## Fuentes de referencia
- [Supabase Storage — Buckets](https://supabase.com/docs/guides/storage/buckets/fundamentals)
- [Supabase Auth con Google](https://supabase.com/docs/guides/auth/social-sign-in/auth-google)
- [Yape — cómo pagar con QR](https://www.yape.com.pe/preguntas-frecuentes/enviar-y-recibir-yapeos/31--como-hago-un-pago-con-qr)
