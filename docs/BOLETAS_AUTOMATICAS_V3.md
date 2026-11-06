# Boletas automáticas V3 — JM Aromas

## Estado actual

La API detecta nuevas compras con pago confirmado y crea una solicitud privada de boleta por pedido.
No se crean comprobantes, XML tributarios, PDFs fiscales ni correlativos en esta etapa.
Los pedidos marcados como prueba quedan excluidos. Se mantiene la emisión manual SEE-SOL como alternativa.

**Emisión real: desactivada.** No activar en producción sin verificar la habilitación del RUC.

## Piezas instaladas

- `database/boletas_automaticas_v3.sql`: cola de solicitudes y disparador al confirmar pago.
- `database/boletas_v3_modalidades.sql`: selector de modalidad, inicialmente POR_CONFIRMAR.
- `BoletasAutomaticasAdminController`: bandeja y preparación comercial de cada venta.
- `SunatSoapClient`: transporte técnico SOAP para resúmenes y consulta de tickets, todavía no conectado al flujo real.
- Angular `admin/boletas`: seguimiento de solicitudes y control de habilitación.

## Requisitos antes de emitir

1. SUNAT debe confirmar para el RUC la modalidad autorizada: SEE del Contribuyente o SEE-OSE. No asumir una modalidad por emitir actualmente en SEE-SOL.
2. Certificado digital de firma y usuario de integración autorizados.
3. Serie propia de esa modalidad, distinta de EB01 emitida mediante SEE-SOL.
4. Generar, validar y firmar XML UBL 2.1 según reglas SUNAT y clasificación tributaria del Nuevo RUS.
5. Generar y firmar Resumen Diario 2.0; enviarlo, conservar ticket y validar CDR.
6. Registrar los archivos privados de forma segura, y solo entonces entregar la representación al cliente mediante Resend y Mi perfil.
7. Probar en beta y después habilitar producción con aprobación expresa del titular.

No subir información sensible de SUNAT ni archivos de firma al repositorio público o frontend.

## Documentación oficial

- https://cpe.sunat.gob.pe/sistema_emision/see_contribuyente
- https://cpe.sunat.gob.pe/informacion_general/operador_servicios_electronicos
- https://cpe.sunat.gob.pe/certificado-digital
- https://cpe.sunat.gob.pe/guias-y-manuales
- https://orientacion.sunat.gob.pe/01-servicios-web-disponibles
