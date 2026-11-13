-- Categorías adicionales y presentación en gramos (migración aditiva).
-- Ejecutar en Supabase antes de desplegar el backend que usa contenido_g.
insert into public.categorias (nombre,slug,activa) values
 ('Perfumes','perfumes',true),
 ('Cuidado personal','cuidado-personal',true),
 ('Cuidado de rostro','cuidado-de-rostro',true),
 ('Maquillaje','maquillaje',true),
 ('Bebés y niños','bebes-y-ninos',true)
on conflict (slug) do update set nombre=excluded.nombre,activa=true;

alter table public.productos add column if not exists contenido_g integer;
do $$ begin
 if not exists (select 1 from pg_constraint where conrelid='public.productos'::regclass and conname='productos_contenido_g_check') then
  alter table public.productos add constraint productos_contenido_g_check
   check (contenido_g is null or contenido_g between 1 and 20000);
 end if;
 if not exists (select 1 from pg_constraint where conrelid='public.productos'::regclass and conname='productos_presentacion_unica_check') then
  alter table public.productos add constraint productos_presentacion_unica_check
   check (contenido_g is null or contenido_ml is null);
 end if;
end $$;
