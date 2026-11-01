# JM-AROMAS API — Primera iteración

## Stack
- Java 21 / Spring Boot 4.1.2 / Maven
- PostgreSQL en Supabase (credenciales privadas del backend)
- Angular en `JM-AROMAS-FRONT` cuando se habilite la integración.

## Endpoints implementados
- `GET /api/v1/health` — respuesta estática que no requiere base de datos.
- `GET /api/v1/productos?pagina=0&tamano=12` — consulta pública paginada, máximo 48 productos por página.
- `GET /api/v1/productos/{slug}` — detalle de un producto activo/publicado; 404 si no existe.

Estos endpoints están preparados para las tablas de `database/schema.sql`. No se han podido probar contra Supabase: falta visibilidad del proyecto `jm-aromas` desde esta conexión.

## Ejecución local
1. Instalar JDK 21 y Maven.
2. Configurar las variables `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD` en la terminal o en el entorno seguro de ejecución. El archivo `.env.example` **no se carga automáticamente** en Spring Boot.
3. Usar `mvn spring-boot:run`.
4. Probar `curl http://localhost:8080/api/v1/health`.
5. Con esquema aplicado y DB conectada, probar `curl http://localhost:8080/api/v1/productos`.

## Seguridad y límites actuales
El backend solo implementa lectura del catálogo. No hay rutas de administración ni compra, ni autenticación implementada todavía. **No usar esta primera iteración como tienda operativa.** La lectura usa SQL parametrizado. No exponer credenciales PostgreSQL en el frontend.

El siguiente incremento debe integrar Supabase Auth y validar JWT antes de habilitar cualquier operación de perfil, pedidos, descuentos o stock.
