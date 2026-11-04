# JM-AROMAS · API (Spring Boot)

Backend de la tienda de perfumes JM-AROMAS. Implementado con **Java 21, Spring Boot 4.1.2, JDBC y PostgreSQL (Supabase)**.

## Arquitectura

Consulta [ARQUITECTURA_BACK.md](docs/ARQUITECTURA_BACK.md) para la estructura por módulos y las responsabilidades de controller, service, repository y domain.

- **Autenticación:** valida sesiones de Google/Supabase en servidor. Las rutas administrativas exigen autorización contra la tabla privada de administradores.
- **Catálogo:** API pública de perfumes publicados, fotografías, filtros de datos y precios con promociones vigentes.
- **Inventario:** registro y edición, código de barras, stock con movimientos auditables y publicación.
- **Promociones:** descuentos porcentuales o de monto fijo con vigencia y sin solapamientos activos.
- **Fotografías:** Supabase Storage, con subida/borrado autenticados y hasta cinco imágenes por producto.
- **Cobros:** configuración de medios (Yape, transferencia y WhatsApp) y revisión de comprobantes.
- **Checkout:** la coordinación por WhatsApp funciona con `CHECKOUT_WHATSAPP_ENABLED=true` (valor predeterminado), independientemente de `CHECKOUT_ENABLED=false`; los cobros web permanecen deshabilitados. El comprador debe pulsar **Enviar** en WhatsApp.
- **Pedidos WhatsApp:** panel de gestión, reserva de stock de 24 h, límite por teléfono, estados, dirección de entrega y comprobación manual de pagos. Antes de abrir a tráfico real, completar CAPTCHA/WAF y pruebas end-to-end.

La separación de capas es progresiva: catálogo usa Controller → Service → Repository. Inventario y descuentos ya separan Controller → Service; algunos servicios y controladores legacy aún tienen SQL y deben refactorizarse con pruebas adicionales.

## Ejecutar localmente

Requisitos: Java 21, Maven 3.9+ y PostgreSQL/Supabase accesible.

1. Configura las variables de entorno siguiendo `.env.example`. Mantén `.env.local` fuera de Git.
2. Ejecuta:

```bash
set -a
source .env.local
set +a
mvn clean test
mvn spring-boot:run
```

No copies claves de Supabase con privilegios de servidor al frontend. Tampoco subas credenciales de PostgreSQL, Gmail ni OAuth a GitHub.

## Datos y documentación

- `database/`: esquema y migraciones SQL incrementales.
- `docs/`: OAuth, fotografías, arquitectura y flujos de negocio.
- `src/test/`: pruebas JUnit (inicialmente validación y normalización GTIN).
- Branch de desarrollo: `feature/mvp-base`; no se ha alterado `main` en esta refactorización.

## Alcance pendiente

Pruebas de integración de checkout, reservas y pagos; extracción completa de repositorios para controladores legacy; monitoreo y trazabilidad antes de producción. Un pedido pendiente no equivale a una venta cobrada.
