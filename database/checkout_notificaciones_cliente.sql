-- JM Aromas: outbox transaccional de notificaciones de estados.
-- Los cambios se capturan en la misma transacción que actualiza el pedido.
-- Nunca se almacenan tokens privados de seguimiento ni credenciales SMTP.
create table if not exists app_private.notificaciones_estado_pedido (
  id bigint generated always as identity primary key,
  pedido_id uuid not null references public.pedidos(id) on delete cascade,
  destinatario text not null check (length(destinatario) between 6 and 254),
  codigo text not null,
  estado_pedido text not null,
  estado_pago text not null,
  evento text not null check (evento in ('CREADO','ESTADO_PEDIDO','ESTADO_PAGO','AMBOS')),
  estado_envio text not null default 'PENDIENTE'
    check (estado_envio in ('PENDIENTE','EN_PROCESO','ENVIADO','ERROR')),
  intentos integer not null default 0 check (intentos >= 0),
  tomado_en timestamptz,
  enviado_en timestamptz,
  ultimo_error text,
  creado_en timestamptz not null default now()
);
create index if not exists notificaciones_estado_pendientes_idx
  on app_private.notificaciones_estado_pedido(estado_envio,creado_en,id)
  where estado_envio in ('PENDIENTE','ERROR');
alter table app_private.notificaciones_estado_pedido enable row level security;
revoke all on table app_private.notificaciones_estado_pedido from public, anon, authenticated;

create or replace function app_private.encolar_notificacion_estado_pedido()
returns trigger language plpgsql
security definer set search_path = ''
as $$
declare
  correo text;
  tipo text;
  pedido_cambio boolean;
  pago_cambio boolean;
begin
  if TG_OP = 'INSERT' then
    tipo := 'CREADO';
  else
    pedido_cambio := old.estado is distinct from new.estado;
    pago_cambio := old.estado_pago is distinct from new.estado_pago;
    if not pedido_cambio and not pago_cambio then
      return new;
    end if;
    tipo := case
      when pedido_cambio and pago_cambio then 'AMBOS'
      when pedido_cambio then 'ESTADO_PEDIDO'
      else 'ESTADO_PAGO'
    end;
  end if;

  correo := lower(btrim(new.cliente_snapshot->>'email'));
  if correo is null or length(correo) not between 6 and 254
     or correo !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$' then
    return new;
  end if;

  insert into app_private.notificaciones_estado_pedido
    (pedido_id,destinatario,codigo,estado_pedido,estado_pago,evento)
  values (new.id,correo,new.codigo,new.estado,new.estado_pago,tipo);
  return new;
end;
$$;
revoke all on function app_private.encolar_notificacion_estado_pedido()
  from public, anon, authenticated;
drop trigger if exists trg_notificar_estado_pedido on public.pedidos;
create trigger trg_notificar_estado_pedido
after insert or update of estado,estado_pago on public.pedidos
for each row execute function app_private.encolar_notificacion_estado_pedido();
