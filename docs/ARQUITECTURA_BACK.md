# Arquitectura del backend JM-AROMAS

## Estructura actual

```text
src/main/java/pe/jmaromas/api/
├── admin/
│   ├── InventarioController.java         # HTTP /api/v1/admin/productos
│   ├── DescuentosAdminController.java     # HTTP /admin/productos/{id}/descuentos
│   ├── ImagenesProductoController.java    # API de fotografías (Storage)
│   ├── GestionMediosPagoController.java   # Medios de cobro
│   ├── RevisarPagosController.java        # Revisión de comprobantes
│   ├── AdminAuthFilter.java               # JWT y rol ADMIN servidor
│   ├── service/
│   │   ├── InventarioService.java         # Reglas y transacciones inventario
│   │   └── DescuentosService.java         # Reglas y transacciones descuentos
│   └── domain/
│       └── CodigoBarras.java             # Normalizador GTIN independiente
├── auth/                                   # Identidad cliente y administrador
├── catalogo/
│   ├── CatalogoController.java            # Contrato HTTP público
│   ├── CatalogoService.java               # Caso de uso
│   ├── CatalogoRepository.java            # JDBC y mapeo SQL
│   └── ProductoPublico.java               # DTO público
├── checkout/                               # Reservas/pedidos, avisos y comprobantes
├── config/                                 # CORS y configuración
└── JmAromasApplication.java
src/test/java/pe/jmaromas/api/admin/domain/CodigoBarrasTest.java
```

## Flujo de dependencias

- `Controller → Service → Repository → PostgreSQL` en el catálogo.
- En inventario y descuentos: `Controller → Service → JdbcTemplate → PostgreSQL`; sus repositorios SQL se pueden extraer en una siguiente fase, evitando modificar de una vez operaciones transaccionales delicadas.
- `AdminAuthFilter` sigue protegiendo **todas** las rutas `/api/v1/admin/**`. Nunca confiar en el rol informado por el navegador.
- Transacciones de inventario, stock y descuentos permanecen en los servicios Spring; los controladores HTTP no abren transacciones.
- El checkout continúa con reglas y queries reunidos en su controlador legacy, para refactorizar en una iteración con pruebas de integración de pedidos y reservas (no migrado aún).
- No se alteraron los nombres ni parámetros de las rutas existentes.
- El bucket público sirve fotografías del catálogo; el bucket de comprobantes conserva acceso privado.

## Reglas para seguir desarrollando

1. Controlador: `@RestController`, solicitud HTTP, DTO y respuesta. **No escribir SQL en controladores nuevos**.
2. Servicio: validaciones y operaciones de negocio; `@Transactional` cuando una operación actualiza varias tablas.
3. Repositorio: JDBC con consultas parametrizadas y mapeo explícito.
4. Dominio: reglas puras (por ejemplo, códigos de barras) verificables con pruebas unitarias.
5. Seguridad: nunca mostrar `SUPABASE_SERVICE_ROLE_KEY` ni credenciales PostgreSQL en el frontend.

## Verificación

GitHub Actions ejecuta ahora `mvn -B test` con Java 21, incluyendo compilación y pruebas de normalización GTIN.
Para verificar localmente: `mvn clean test`. Iniciar la API: `mvn spring-boot:run` con las variables del entorno local cargadas.

Esto es una **refactorización progresiva**: no equivale a declarar finalizada la separación de todas las clases.
