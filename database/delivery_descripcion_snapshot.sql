-- Registrar la descripción de entrega calculada por backend como snapshot inmutable.
-- El catálogo puede cambiar y debe preservarse el detalle de cobertura original.
alter table public.pedidos
  add column if not exists descripcion_entrega text not null default 'Envío por coordinar';
comment on column public.pedidos.descripcion_entrega is
 'Destino y modalidad de envío capturados al crear el pedido; no dependen del catálogo posterior.';
