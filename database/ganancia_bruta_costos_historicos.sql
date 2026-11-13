-- Aplicar en Supabase ANTES de desplegar el backend con snapshots de costo.
-- No se recalculan costos históricos con los precios de compra actuales:
-- operaciones anteriores quedan sin snapshot y su ganancia se indica como no disponible.
create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;

alter table app_private.ventas_presenciales_detalle
  add column if not exists costo_unitario_snapshot numeric(12,2)
    check (costo_unitario_snapshot >= 0);

create table if not exists app_private.costos_detalle_pedido (
  pedido_id uuid not null references public.pedidos(id) on delete cascade,
  producto_id uuid not null,
  costo_unitario_snapshot numeric(12,2)
    check (costo_unitario_snapshot >= 0),
  creado_en timestamptz not null default now(),
  primary key (pedido_id, producto_id)
);

alter table app_private.costos_detalle_pedido enable row level security;
revoke all on app_private.costos_detalle_pedido from public, anon, authenticated;
revoke all on app_private.ventas_presenciales_detalle from public, anon, authenticated;

comment on table app_private.costos_detalle_pedido is
 'Snapshot privado del costo por unidad del producto cuando se registra un pedido web; NULL significa costo desconocido.';
