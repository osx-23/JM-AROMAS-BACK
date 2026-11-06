-- JM Aromas: opciones de entrega calculadas por servidor y seguimiento logístico.
-- Se conservan pedidos antiguos con POR_COORDINAR / costo de entrega existente.
alter table public.pedidos
  add column if not exists metodo_entrega text not null default 'POR_COORDINAR',
  add column if not exists zona_entrega_id text,
  add column if not exists agencia_shalom text;

alter table public.pedidos drop constraint if exists pedidos_metodo_entrega_check;
alter table public.pedidos add constraint pedidos_metodo_entrega_check
  check (metodo_entrega in ('POR_COORDINAR','DELIVERY_LIMA','SHALOM'));

alter table public.pedidos drop constraint if exists pedidos_destino_entrega_check;
alter table public.pedidos add constraint pedidos_destino_entrega_check
  check (
    (metodo_entrega='POR_COORDINAR' and zona_entrega_id is null and agencia_shalom is null)
    or (metodo_entrega='DELIVERY_LIMA' and zona_entrega_id is not null and agencia_shalom is null)
    or (metodo_entrega='SHALOM' and agencia_shalom is not null and zona_entrega_id is null)
  );
alter table public.pedidos drop constraint if exists pedidos_estado_check;
alter table public.pedidos add constraint pedidos_estado_check
  check (estado in (
    'PENDIENTE','CONTACTADO','CONFIRMADO','PREPARANDO',
    'EN_CAMINO','FINALIZADO','ENTREGADO','CANCELADO'
  ));
comment on column public.pedidos.metodo_entrega is
 'POR_COORDINAR para pedidos anteriores. Nuevos pedidos DELIVERY_LIMA o SHALOM.';
comment on column public.pedidos.agencia_shalom is
 'Referencia al nombre de sede Shalom elegida; verificar su operatividad al despachar.';
