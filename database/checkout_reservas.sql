-- Reserva temporal para impedir sobreventa durante pago manual.
-- Para compras sin comprobante la reserva expira tras 24h.
alter table public.pedidos
  add column medio_pago_id uuid references app_private.medios_pago(id) on delete set null,
  add column expira_en timestamptz;
create index pedidos_reservas_expiradas_idx
  on public.pedidos(expira_en)
  where estado='PENDIENTE' and estado_pago='PENDIENTE';
