-- JM-AROMAS | Checkout manual seguro y compras como invitado.
-- No incluye cuentas, números de Yape ni datos personales reales.
-- La confirmación del abono solo puede realizarla un administrador desde backend.
alter table public.pedidos alter column usuario_id drop not null;
alter table public.pedidos
  add column cliente_snapshot jsonb not null default '{}'::jsonb
    check (jsonb_typeof(cliente_snapshot)='object'),
  add column acceso_invitado_hash text unique,
  add column confirmacion_pago_en timestamptz,
  add column confirmado_pago_por uuid references auth.users(id) on delete set null;

alter table public.pedidos drop constraint pedidos_metodo_pago_check;
alter table public.pedidos add constraint pedidos_metodo_pago_check
  check (metodo_pago in ('CONTRA_ENTREGA','COORDINAR_WHATSAPP','YAPE','TRANSFERENCIA'));

alter table public.pedidos drop constraint pedidos_estado_pago_check;
alter table public.pedidos add constraint pedidos_estado_pago_check
  check (estado_pago in ('PENDIENTE','PENDIENTE_CONFIRMACION','PAGADO','RECHAZADO','ANULADO'));

alter table public.pedidos add constraint pedido_identidad_valida
  check ((usuario_id is null and acceso_invitado_hash is not null) or
         (usuario_id is not null));
alter table public.pedidos add constraint pedido_cliente_datos
  check (cliente_snapshot ? 'nombre' and cliente_snapshot ? 'dni'
         and cliente_snapshot ? 'telefono' and cliente_snapshot ? 'email');
create index if not exists pedidos_pago_pendientes_idx
  on public.pedidos(estado_pago, creado_en desc)
  where estado_pago='PENDIENTE_CONFIRMACION';

-- Datos de perfil de cliente opcionales. Siguen las políticas RLS ya existentes.
alter table public.perfiles add column dni text
  check (dni is null or dni ~ '^[0-9]{8}$');

-- El administrador configura Yape, bancos y WhatsApp desde entorno autorizado.
-- Varios bancos son múltiples filas con tipo TRANSFERENCIA.
create table app_private.medios_pago (
  id uuid primary key default gen_random_uuid(),
  tipo text not null check(tipo in ('YAPE','TRANSFERENCIA','WHATSAPP')),
  titulo text not null check(length(trim(titulo)) between 3 and 120),
  titular text,
  numero_yape text,
  qr_url text,
  banco text,
  numero_cuenta text,
  cci text,
  telefono_whatsapp text,
  activo boolean not null default false,
  orden integer not null default 0,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  constraint medio_pago_configurado check (
    not activo or
    (tipo='YAPE' and titular is not null and numero_yape is not null and qr_url is not null) or
    (tipo='TRANSFERENCIA' and titular is not null and banco is not null and numero_cuenta is not null) or
    (tipo='WHATSAPP' and telefono_whatsapp is not null)
  )
);
create index medios_pago_activos_idx on app_private.medios_pago(tipo, orden)
  where activo;

-- Fotos/capturas de pago: solo en un bucket PRIVADO. No se expone la ruta al comprador.
create table app_private.comprobantes_pago (
  pedido_id uuid primary key references public.pedidos(id) on delete cascade,
  medio_pago_id uuid references app_private.medios_pago(id) on delete set null,
  ruta_storage text not null unique,
  mime text not null check(mime in ('image/jpeg','image/png','image/webp')),
  tamano_bytes integer not null check(tamano_bytes between 1 and 5242880),
  estado text not null default 'PENDIENTE_CONFIRMACION'
    check(estado in ('PENDIENTE_CONFIRMACION','CONFIRMADO','RECHAZADO')),
  comentario_revision text,
  revisado_por uuid references auth.users(id) on delete set null,
  creado_en timestamptz not null default now(),
  revisado_en timestamptz
);

-- Bandeja de notificaciones al administrador. No depender de que el correo salga
-- para registrar y proteger el comprobante.
create table app_private.notificaciones_pago (
  id bigserial primary key,
  pedido_id uuid not null references public.pedidos(id) on delete cascade,
  clase text not null default 'COMPROBANTE_SUBIDO',
  estado text not null default 'PENDIENTE'
    check(estado in ('PENDIENTE','ENVIADO','ERROR')),
  intentos integer not null default 0 check(intentos>=0),
  ultimo_error text,
  creado_en timestamptz not null default now(),
  enviado_en timestamptz
);
create index notificaciones_pago_pendientes_idx
  on app_private.notificaciones_pago(estado, creado_en)
  where estado in ('PENDIENTE','ERROR');

alter table app_private.medios_pago enable row level security;
alter table app_private.comprobantes_pago enable row level security;
alter table app_private.notificaciones_pago enable row level security;
revoke all on app_private.medios_pago, app_private.comprobantes_pago,
  app_private.notificaciones_pago from public, anon, authenticated;

-- Crear bucket privado con límite explícito. No conceder policies públicas.
insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values('jm-aromas-comprobantes','jm-aromas-comprobantes',false,5242880,
       array['image/jpeg','image/png','image/webp']::text[])
on conflict (id) do nothing;

-- Los pedidos guest nunca pueden leerse a través de PostgREST sin autenticación.
-- Su consulta se hace por API usando un token secreto de alta entropía.
