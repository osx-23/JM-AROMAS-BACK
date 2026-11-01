-- JM-AROMAS | esquema inicial propuesto (PostgreSQL / Supabase).
-- Solo ejecutar en el proyecto Supabase jm-aromas DESPUES de verificar su referencia.
-- No elimina tablas ni datos existentes. Esquema para una base nueva.
-- El checkout, los descuentos y los movimientos de stock se ejecutan en transacciones del backend.
-- No se insertan productos de ejemplo ni administradores falsos.

create schema if not exists app_private;
revoke all on schema app_private from public;
revoke all on schema app_private from anon, authenticated;

-- El primer ADMIN se designa por SQL desde un entorno confiable, NUNCA por el navegador.
create table app_private.administradores (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  creado_en timestamptz not null default now()
);

create table app_private.configuracion (
  clave text primary key,
  valor jsonb not null,
  actualizado_en timestamptz not null default now()
);

-- Sin datos sensibles ni rol editable por el cliente.
create table public.perfiles (
  usuario_id uuid primary key references auth.users(id) on delete cascade,
  nombre text not null check (length(trim(nombre)) between 1 and 150),
  telefono text check (telefono is null or length(telefono) <= 30),
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);

create table public.direcciones (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  destinatario text not null check (length(trim(destinatario)) >= 1),
  telefono text not null check (length(trim(telefono)) >= 6),
  departamento text not null,
  provincia text not null,
  distrito text not null,
  linea_direccion text not null,
  referencia text,
  principal boolean not null default false,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);
create index direcciones_usuario_idx on public.direcciones(usuario_id);
create unique index direcciones_principal_unica_idx
  on public.direcciones(usuario_id) where principal = true;

create table public.categorias (
  id uuid primary key default gen_random_uuid(),
  nombre text not null check (length(trim(nombre)) >= 1),
  slug text not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  activa boolean not null default true,
  creada_en timestamptz not null default now()
);

create table public.productos (
  id uuid primary key default gen_random_uuid(),
  categoria_id uuid references public.categorias(id) on delete set null,
  nombre text not null check (length(trim(nombre)) >= 1),
  slug text not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  marca text,
  descripcion text,
  sku text not null unique,
  codigo_barras text unique,
  precio numeric(12,2) not null check (precio >= 0),
  stock integer not null default 0 check (stock >= 0),
  activo boolean not null default true,
  publicado boolean not null default false,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);
create index productos_categoria_idx on public.productos(categoria_id);
create index productos_catalogo_idx on public.productos(publicado, activo);

create table public.imagenes_producto (
  id uuid primary key default gen_random_uuid(),
  producto_id uuid not null references public.productos(id) on delete cascade,
  ruta_storage text not null,
  descripcion_alt text,
  orden integer not null default 0 check (orden >= 0),
  creada_en timestamptz not null default now()
);
create index imagenes_producto_idx on public.imagenes_producto(producto_id, orden);

create table public.descuentos (
  id uuid primary key default gen_random_uuid(),
  producto_id uuid not null references public.productos(id) on delete cascade,
  nombre text not null,
  tipo text not null check (tipo in ('PORCENTAJE','MONTO')),
  valor numeric(12,2) not null check (valor > 0),
  inicio timestamptz not null,
  fin timestamptz not null,
  activo boolean not null default true,
  creado_en timestamptz not null default now(),
  constraint descuento_fechas_validas check (fin > inicio),
  constraint descuento_porcentaje_valido check (tipo <> 'PORCENTAJE' or valor <= 100)
);
create index descuentos_producto_vigencia_idx on public.descuentos(producto_id, inicio, fin);
-- Regla por implementar en backend: determinar un descuento aplicable; NO apilarlos por defecto.

create table public.zonas_entrega (
  id uuid primary key default gen_random_uuid(),
  nombre text not null,
  departamento text not null,
  provincia text not null,
  distrito text not null,
  costo numeric(12,2) not null default 0 check (costo >= 0),
  habilitada boolean not null default false
);

-- Cualquier compra se registra en backend, no desde una tabla pública anónima.
create table public.pedidos (
  id uuid primary key default gen_random_uuid(),
  codigo text not null unique,
  usuario_id uuid not null references auth.users(id) on delete restrict,
  idempotencia uuid not null unique,
  direccion_snapshot jsonb not null check (jsonb_typeof(direccion_snapshot) = 'object'),
  metodo_pago text not null check (metodo_pago in ('CONTRA_ENTREGA','COORDINAR_WHATSAPP')),
  estado text not null default 'PENDIENTE'
    check (estado in ('PENDIENTE','CONTACTADO','CONFIRMADO','PREPARANDO','ENTREGADO','CANCELADO')),
  estado_pago text not null default 'PENDIENTE'
    check (estado_pago in ('PENDIENTE','PAGADO','ANULADO')),
  subtotal numeric(12,2) not null check (subtotal >= 0),
  descuento_total numeric(12,2) not null default 0 check (descuento_total >= 0),
  costo_entrega numeric(12,2) not null default 0 check (costo_entrega >= 0),
  total numeric(12,2) not null check (total >= 0),
  notas_cliente text,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  constraint pedido_total_coherente check (total = subtotal - descuento_total + costo_entrega)
);
create index pedidos_usuario_creacion_idx on public.pedidos(usuario_id, creado_en desc);
create index pedidos_estado_creacion_idx on public.pedidos(estado, creado_en desc);

create table public.detalles_pedido (
  id uuid primary key default gen_random_uuid(),
  pedido_id uuid not null references public.pedidos(id) on delete cascade,
  producto_id uuid references public.productos(id) on delete set null,
  sku_snapshot text not null,
  nombre_snapshot text not null,
  cantidad integer not null check (cantidad > 0),
  precio_unitario numeric(12,2) not null check (precio_unitario >= 0),
  descuento_unitario numeric(12,2) not null default 0 check (descuento_unitario >= 0),
  total_linea numeric(12,2) not null check (total_linea >= 0),
  constraint precio_neto_valido check (descuento_unitario <= precio_unitario),
  constraint total_linea_coherente
    check (total_linea = (precio_unitario - descuento_unitario) * cantidad)
);
create index detalles_pedido_idx on public.detalles_pedido(pedido_id);

create table public.historial_pedido (
  id uuid primary key default gen_random_uuid(),
  pedido_id uuid not null references public.pedidos(id) on delete cascade,
  estado_anterior text,
  estado_nuevo text not null,
  observacion text,
  actualizado_por uuid references auth.users(id) on delete set null,
  creado_en timestamptz not null default now()
);
create index historial_pedido_idx on public.historial_pedido(pedido_id, creado_en);

create table public.movimientos_stock (
  id uuid primary key default gen_random_uuid(),
  producto_id uuid not null references public.productos(id) on delete restrict,
  pedido_id uuid references public.pedidos(id) on delete set null,
  cantidad_delta integer not null check (cantidad_delta <> 0),
  stock_anterior integer not null check (stock_anterior >= 0),
  stock_nuevo integer not null check (stock_nuevo >= 0),
  motivo text not null,
  realizado_por uuid references auth.users(id) on delete set null,
  creado_en timestamptz not null default now(),
  constraint movimiento_stock_coherente check (stock_nuevo = stock_anterior + cantidad_delta)
);
create index movimientos_stock_producto_idx on public.movimientos_stock(producto_id, creado_en desc);

-- Seguridad: RLS en TODAS las tablas expuestas por public.
alter table public.perfiles enable row level security;
alter table public.direcciones enable row level security;
alter table public.categorias enable row level security;
alter table public.productos enable row level security;
alter table public.imagenes_producto enable row level security;
alter table public.descuentos enable row level security;
alter table public.zonas_entrega enable row level security;
alter table public.pedidos enable row level security;
alter table public.detalles_pedido enable row level security;
alter table public.historial_pedido enable row level security;
alter table public.movimientos_stock enable row level security;
alter table app_private.administradores enable row level security;
alter table app_private.configuracion enable row level security;

-- Eliminar privilegios por defecto; asignar solo el mínimo necesario.
revoke all on public.perfiles, public.direcciones, public.categorias,
  public.productos, public.imagenes_producto, public.descuentos,
  public.zonas_entrega, public.pedidos, public.detalles_pedido,
  public.historial_pedido, public.movimientos_stock from anon, authenticated;

grant select on public.categorias, public.productos,
  public.imagenes_producto, public.descuentos, public.zonas_entrega
  to anon, authenticated;
grant select, insert, update on public.perfiles to authenticated;
grant select, insert, update, delete on public.direcciones to authenticated;
grant select on public.pedidos, public.detalles_pedido,
  public.historial_pedido to authenticated;

-- Lectura pública solo de catálogo publicado.
create policy categorias_publicas on public.categorias for select
  to anon, authenticated using (activa);
create policy productos_publicados on public.productos for select
  to anon, authenticated using (activo and publicado);
create policy imagenes_de_productos_publicados on public.imagenes_producto for select
  to anon, authenticated using (
    exists(select 1 from public.productos p
           where p.id = producto_id and p.activo and p.publicado)
  );
create policy descuentos_publicados_vigentes on public.descuentos for select
  to anon, authenticated using (
    activo and now() >= inicio and now() < fin
    and exists(select 1 from public.productos p
               where p.id = producto_id and p.activo and p.publicado)
  );
create policy zonas_habilitadas on public.zonas_entrega for select
  to anon, authenticated using (habilitada);

-- Clientes: solo pueden operar sobre sus propios perfiles y direcciones.
create policy perfil_propio_lectura on public.perfiles for select
  to authenticated using (usuario_id = (select auth.uid()));
create policy perfil_propio_creacion on public.perfiles for insert
  to authenticated with check (usuario_id = (select auth.uid()));
create policy perfil_propio_edicion on public.perfiles for update
  to authenticated using (usuario_id = (select auth.uid()))
  with check (usuario_id = (select auth.uid()));

create policy direccion_propia_lectura on public.direcciones for select
  to authenticated using (usuario_id = (select auth.uid()));
create policy direccion_propia_creacion on public.direcciones for insert
  to authenticated with check (usuario_id = (select auth.uid()));
create policy direccion_propia_edicion on public.direcciones for update
  to authenticated using (usuario_id = (select auth.uid()))
  with check (usuario_id = (select auth.uid()));
create policy direccion_propia_eliminacion on public.direcciones for delete
  to authenticated using (usuario_id = (select auth.uid()));

-- Los clientes pueden VER sus pedidos. Las escrituras solo desde backend confiable.
create policy pedido_propio_lectura on public.pedidos for select
  to authenticated using (usuario_id = (select auth.uid()));
create policy detalles_pedido_propios on public.detalles_pedido for select
  to authenticated using (
    exists(select 1 from public.pedidos p
           where p.id = pedido_id and p.usuario_id = (select auth.uid()))
  );
create policy historial_pedido_propio on public.historial_pedido for select
  to authenticated using (
    exists(select 1 from public.pedidos p
           where p.id = pedido_id and p.usuario_id = (select auth.uid()))
  );

-- Sin policies públicas para movimientos_stock ni app_private.
-- No existe opción de autoasignarse el rol ADMIN.
-- El backend tendrá que validar JWT y confirmar permisos antes de utilizar credenciales privilegiadas.
