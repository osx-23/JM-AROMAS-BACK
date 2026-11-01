-- Segunda migración de JM-AROMAS.
-- Aplicada a Supabase JM-AROMAS (gonhxfvelgxnjqpdyzpg)
-- con nombre jm_aromas_indices_relaciones.
-- Estas claves foráneas se indexan para mejorar consultas y joins.
create index if not exists detalles_pedido_producto_idx
  on public.detalles_pedido (producto_id);
create index if not exists historial_pedido_actualizado_por_idx
  on public.historial_pedido (actualizado_por);
create index if not exists movimientos_stock_pedido_idx
  on public.movimientos_stock (pedido_id);
create index if not exists movimientos_stock_realizado_por_idx
  on public.movimientos_stock (realizado_por);
