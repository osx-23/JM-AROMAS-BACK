-- Fotos de perfumes de JM-AROMAS.
-- La tabla public.imagenes_producto y sus políticas RLS ya existen en database/schema.sql.
-- Solo escritura de imágenes por la API protegida de administrador; lectura pública del bucket.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('jm-aromas-productos','jm-aromas-productos',true,5242880,
       array['image/jpeg','image/png','image/webp']::text[])
on conflict (id) do nothing;

-- Los permisos de subida/borrado NO se conceden a anon ni authenticated.
-- El backend escribe con service_role después de validar Google Auth + rol ADMIN.
