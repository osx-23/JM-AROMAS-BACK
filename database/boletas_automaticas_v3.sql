-- V3 JM Aromas: captura transaccional de ventas cobradas para la futura emisión SEE.
-- No crea boletas, correlativos, XML válidos ni envíos a SUNAT.
-- La habilitación tributaria debe comprobarse antes de activar un emisor real.
alter table public.pedidos
  add column if not exists es_prueba boolean not null default false;

create table if not exists app_private.boletas_automaticas (
  id uuid primary key default gen_random_uuid(),
  pedido_id uuid not null unique references public.pedidos(id) on delete restrict,
  estado text not null default 'PENDIENTE_CONFIGURACION'
    check (estado in ('PENDIENTE_CONFIGURACION','LISTA_PARA_EMITIR',
                    'GENERANDO','ENVIADA','ACEPTADA','RECHAZADA','ERROR','DESCARTADA')),
  modalidad text not null default 'SEE_CONTRIBUYENTE'
    check (modalidad='SEE_CONTRIBUYENTE'),
  serie varchar(4) check (serie is null or serie ~ '^B[A-Z0-9]{3}$'),
  numero bigint check (numero is null or numero between 1 and 99999999),
  xml_sha256 text,
  ticket_sunat text,
  cdr_codigo text,
  cdr_descripcion text,
  intentos integer not null default 0 check(intentos>=0),
  ultimo_error text,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  constraint auto_serie_numero_pareja check (
    (serie is null and numero is null) or (serie is not null and numero is not null)),
  constraint auto_serie_numero_unicos unique (serie,numero)
);
create index if not exists boletas_auto_estado_idx
 on app_private.boletas_automaticas(estado,creado_en)
 where estado in ('PENDIENTE_CONFIGURACION','LISTA_PARA_EMITIR','ERROR');
alter table app_private.boletas_automaticas enable row level security;
revoke all on app_private.boletas_automaticas from public,anon,authenticated;
comment on table app_private.boletas_automaticas is
 'Cola de órdenes para futura emisión SEE del Contribuyente. PENDIENTE_CONFIGURACION NO es boleta emitida.';

create or replace function app_private.encolar_boleta_automatica_pagada()
returns trigger language plpgsql
security definer set search_path=''
as $$
begin
  if old.estado_pago is distinct from 'PAGADO'
     and new.estado_pago = 'PAGADO'
     and new.estado <> 'CANCELADO'
     and not new.es_prueba
     and not exists (
         select 1 from app_private.boletas_manuales
         where pedido_id=new.id
     )
  then
    insert into app_private.boletas_automaticas(pedido_id)
    values (new.id)
    on conflict (pedido_id) do nothing;
  end if;
  return new;
end;
$$;
revoke all on function app_private.encolar_boleta_automatica_pagada()
 from public,anon,authenticated;
drop trigger if exists trg_encolar_boleta_automatica_pagada on public.pedidos;
create trigger trg_encolar_boleta_automatica_pagada
 after update of estado_pago on public.pedidos
 for each row
 execute function app_private.encolar_boleta_automatica_pagada();
