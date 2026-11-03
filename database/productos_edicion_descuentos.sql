-- JM-AROMAS: perfil olfativo por público y contenido en ml.
-- Migración aditiva; productos ya registrados permanecen sin clasificar.
alter table public.productos add column if not exists publico_objetivo text;
alter table public.productos add column if not exists contenido_ml integer;
do $$ begin
  if not exists(select 1 from pg_constraint where conrelid='public.productos'::regclass
    and conname='productos_publico_objetivo_check') then
    alter table public.productos add constraint productos_publico_objetivo_check
      check (publico_objetivo is null or publico_objetivo in ('MUJER','HOMBRE','UNISEX'));
  end if;
  if not exists(select 1 from pg_constraint where conrelid='public.productos'::regclass
    and conname='productos_contenido_ml_check') then
    alter table public.productos add constraint productos_contenido_ml_check
      check (contenido_ml is null or contenido_ml between 1 and 2000);
  end if;
end $$;
-- El esquema original ya incluye public.descuentos con fechas, tipos y activo.
-- El backend calcula las promociones vigentes en las compras: nunca confiar en el precio del frontend.

-- Clasificación olfativa para que el filtro existente en el catálogo sea real.
alter table public.productos add column if not exists familia_olfativa text;
do $$ begin
  if not exists(select 1 from pg_constraint where conrelid='public.productos'::regclass
    and conname='productos_familia_olfativa_check') then
    alter table public.productos add constraint productos_familia_olfativa_check
      check (familia_olfativa is null or familia_olfativa in
        ('Floral','Amaderada','Cítrica','Fresca','Oriental','Frutal'));
  end if;
end $$;
