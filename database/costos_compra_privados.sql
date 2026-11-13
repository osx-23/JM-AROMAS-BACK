-- Ejecutar una sola vez en Supabase SQL Editor antes de desplegar el backend.
-- El costo de compra es confidencial: no se guarda en public.productos.
create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;

create table if not exists app_private.productos_costos (
  producto_id uuid primary key references public.productos(id) on delete cascade,
  costo_compra numeric(12,2) not null check (costo_compra >= 0),
  actualizado_en timestamptz not null default now()
);

alter table app_private.productos_costos enable row level security;
revoke all on app_private.productos_costos from public, anon, authenticated;
-- Sin políticas RLS para usuarios públicos. Solo el backend con acceso privilegiado.
-- Los productos existentes quedan sin costo (NULL en la consulta administrativa)
-- hasta que se indique su costo al editar.
