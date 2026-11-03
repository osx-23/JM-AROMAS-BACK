# JM-AROMAS: notificaciones mediante Gmail SMTP

El backend envía avisos de comprobantes a la administración con **Gmail SMTP**, no con Resend. No hay que contratar un dominio ni configurar `RESEND_API_KEY`.

## Preparar la cuenta

1. Iniciar sesión en la cuenta remitente `notificaciones.jmaromas@gmail.com`.
2. Activar la verificación en dos pasos en [Google Cuenta](https://myaccount.google.com/security).
3. Abrir [Contraseñas de aplicación](https://myaccount.google.com/apppasswords) y generar una para **JM-AROMAS-BACK**.
4. Guardar la contraseña de aplicación **solo en el entorno del backend**, sin copiarla a GitHub, Angular ni conversaciones.
5. Si Google no permite generar contraseñas de aplicación, verificar si la cuenta tiene políticas de protección especiales. La alternativa a futuro es Gmail API con OAuth2.

## Configuración de entorno

Crear `.env.local` en la raíz de `JM-AROMAS-BACK` (se encuentra excluido en `.gitignore`):

```bash
SPRING_MAIL_HOST='smtp.gmail.com'
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME='notificaciones.jmaromas@gmail.com'
SPRING_MAIL_PASSWORD='CONTRASENA_DE_APLICACION_GOOGLE'
EMAIL_FROM_ADDRESS='JM-AROMAS <notificaciones.jmaromas@gmail.com>'
ADMIN_NOTIFICATION_EMAIL='notificaciones.jmaromas@gmail.com'
APP_PUBLIC_URL='http://localhost:4200'
```

Mantener el resto de las variables de Supabase y PostgreSQL sin cambios.
Al iniciar localmente:

```bash
chmod 600 .env.local
set -a
source .env.local
set +a
mvn clean compile
mvn spring-boot:run
```

## Comportamiento

- Cuando el cliente adjunta su comprobante, se crea una notificación en `app_private.notificaciones_pago`.
- El job programado envía el aviso al correo de la administradora con un código de pedido y el enlace al panel.
- Solo se envía si están configurados usuario SMTP, contraseña de aplicación, destinatario y remitente válido que **coincida con el usuario SMTP**.
- Si el envío falla, la notificación queda con estado `ERROR` para reintento (hasta cinco intentos).
- Los correos a los **compradores** no están implementados aún. Esta integración solo avisa de nuevos comprobantes al **administrador**.
- No se adjuntan fotos de comprobantes al correo; se consultan con autenticación en el panel administrativo.
- Sin contraseña SMTP, no se envía ningún aviso.

## Seguridad

- Utilizar una cuenta Gmail dedicada, no una cuenta personal de alto valor.
- No incluir credenciales en `application.properties` ni revelar `.env.local`.
- Al desplegar Railway, definir las mismas variables en su gestor seguro de variables.
- Gmail impone límites de envío y puede bloquear accesos. Para alto volumen migrar a un proveedor transaccional con dominio verificado.
- El remitente con nombre visible **JM-AROMAS** se basa en `EMAIL_FROM_ADDRESS`; la dirección Gmail debe coincidir exactamente con `SPRING_MAIL_USERNAME`.

## Documentación oficial

- [Google: contraseñas de aplicación](https://support.google.com/accounts/answer/185833)
- [Google: ajustes SMTP](https://support.google.com/mail/answer/7104828)
- [Spring Boot: enviar correos](https://docs.spring.io/spring-boot/reference/io/email.html)
