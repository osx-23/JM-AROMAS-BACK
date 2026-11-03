-- QR públicos del comercio; solamente el servidor administrativo puede subirlos.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('jm-aromas-medios-pago','jm-aromas-medios-pago',true,3145728,
       array['image/png','image/jpeg','image/webp']::text[])
on conflict(id) do nothing;
-- No se añaden policies públicas de INSERT/UPDATE/DELETE a storage.objects.
