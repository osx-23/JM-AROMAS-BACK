-- Migración V3: elegir modalidad solo después de confirmar habilitación.
-- El Nuevo RUS requiere revisar cuál modalidad SEE admite su RUC.
alter table app_private.boletas_automaticas
  drop constraint if exists boletas_automaticas_modalidad_check;
alter table app_private.boletas_automaticas
  alter column modalidad set default 'POR_CONFIRMAR';
update app_private.boletas_automaticas
  set modalidad='POR_CONFIRMAR',actualizado_en=now()
  where estado='PENDIENTE_CONFIGURACION' and modalidad='SEE_CONTRIBUYENTE';
alter table app_private.boletas_automaticas
  add constraint boletas_automaticas_modalidad_check
    check (modalidad in ('POR_CONFIRMAR','SEE_CONTRIBUYENTE','SEE_OSE'));
comment on column app_private.boletas_automaticas.modalidad is
  'Solo elegir SEE_CONTRIBUYENTE u SEE_OSE cuando SUNAT confirme habilitacion del RUC.';
