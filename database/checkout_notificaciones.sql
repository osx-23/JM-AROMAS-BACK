-- Claim de trabajos de correo para evitar envíos duplicados entre instancias.
alter table app_private.notificaciones_pago drop constraint notificaciones_pago_estado_check;
alter table app_private.notificaciones_pago add constraint notificaciones_pago_estado_check
  check(estado in ('PENDIENTE','EN_PROCESO','ENVIADO','ERROR'));
alter table app_private.notificaciones_pago
  add column tomado_en timestamptz;
-- También indexar reservas rechazadas, que expiran 24h después del rechazo.
drop index if exists public.pedidos_reservas_expiradas_idx;
create index pedidos_reservas_expiradas_idx on public.pedidos(expira_en)
  where estado='PENDIENTE' and estado_pago in ('PENDIENTE','RECHAZADO');
