-- Ventas de mostrador, separadas de los pedidos del e-commerce.
-- Ejecutar antes del despliegue de la nueva API.
create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;

create table if not exists app_private.ventas_presenciales (
  id uuid primary key,
  idempotencia uuid not null unique,
  codigo text not null unique,
  medio_pago text not null check (medio_pago in ('EFECTIVO','YAPE','TRANSFERENCIA','TARJETA')),
  total numeric(12,2) not null check (total >= 0),
  registrado_por uuid references auth.users(id) on delete set null,
  creado_en timestamptz not null default now()
);

create table if not exists app_private.ventas_presenciales_detalle (
  id uuid primary key default gen_random_uuid(),
  venta_id uuid not null references app_private.ventas_presenciales(id) on delete restrict,
  producto_id uuid references public.productos(id) on delete set null,
  sku_snapshot text not null,
  nombre_snapshot text not null,
  cantidad integer not null check (cantidad > 0),
  precio_unitario numeric(12,2) not null check (precio_unitario >= 0),
  total_linea numeric(12,2) not null check (total_linea = cantidad * precio_unitario)
);

create index if not exists ventas_presenciales_fecha_idx
  on app_private.ventas_presenciales(creado_en desc);
create index if not exists ventas_presenciales_detalle_venta_idx
  on app_private.ventas_presenciales_detalle(venta_id);

alter table app_private.ventas_presenciales enable row level security;
alter table app_private.ventas_presenciales_detalle enable row level security;
revoke all on app_private.ventas_presenciales, app_private.ventas_presenciales_detalle
  from public, anon, authenticated;
-- Sin políticas RLS públicas: acceso exclusivamente por backend con JWT admin.
