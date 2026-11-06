-- JM Aromas / Fase 1: registro de BOLETAS REALES previamente emitidas en SUNAT SEE-SOL.
-- IMPORTANTE: este módulo no emite, firma, valida ni comunica CPE a SUNAT.
-- Registro privado accesible exclusivamente desde la API administrativa.
create table if not exists app_private.boletas_manuales (
  id uuid primary key default gen_random_uuid(),
  pedido_id uuid not null unique references public.pedidos(id) on delete restrict,
  emisor_ruc varchar(11) not null check (emisor_ruc ~ '^[0-9]{11}$'),
  tipo_comprobante varchar(2) not null default '03' check(tipo_comprobante='03'),
  modalidad varchar(12) not null default 'SEE_SOL' check(modalidad='SEE_SOL'),
  serie varchar(4) not null check(serie ~ '^[A-Z0-9]{4}$'),
  numero integer not null check(numero between 1 and 99999999),
  fecha_emision date not null,
  moneda varchar(3) not null default 'PEN' check(moneda='PEN'),
  total numeric(12,2) not null check(total>0),
  estado_validacion varchar(20) not null default 'NO_VERIFICADA'
    check(estado_validacion in ('NO_VERIFICADA','VERIFICADA','OBSERVADA','ANULADA')),
  pdf_ruta text,
  registrado_por uuid references auth.users(id) on delete set null,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  constraint boleta_serie_numero_emisor_unicos unique (emisor_ruc,serie,numero)
);
create index if not exists boletas_manuales_creacion_idx
  on app_private.boletas_manuales(creado_en desc);
create index if not exists boletas_manuales_emision_idx
  on app_private.boletas_manuales(fecha_emision desc);

alter table app_private.boletas_manuales enable row level security;
revoke all on app_private.boletas_manuales from public,anon,authenticated;

comment on table app_private.boletas_manuales is
  'Registro manual de boletas ya emitidas en SEE-SOL. NO acredita aceptacion SUNAT ni genera CPE.';
comment on column app_private.boletas_manuales.estado_validacion is
  'NO_VERIFICADA por defecto: registrar una boleta no implica validar su estado ante SUNAT.';
