# 03 — Contratos REST y eventos

## REST mínimo

Todas las APIs bajo `/api`. Documentar cada servicio con OpenAPI/Swagger.

**Catalog**
- `GET /api/events?from=&to=&page=` — listado paginado.
- `GET /api/events/{eventId}` — detalle y tipos de entrada.
- `POST /api/events` — crear (demo admin).
- `POST /api/events/{eventId}/publish` — publicar e inicializar inventario vía evento.

**Booking**
- `GET /api/events/{eventId}/availability`
- `POST /api/waiting-room/{eventId}/join`
- `GET /api/waiting-room/{eventId}/status`
- `POST /api/reservations` — requiere `Idempotency-Key`.
- `GET /api/reservations/{reservationId}`
- `POST /api/reservations/{reservationId}/checkout`
- `POST /api/reservations/{reservationId}/cancel`

**Payment**: interno, no expuesto al frontend; consume `PaymentRequested` y `PaymentRefundRequested`
y publica `PaymentAuthorized` o `PaymentRejected`. **Notification**: `GET /api/notifications?userId=&page=`.

La demo usa un `X-User-Id` sintético detrás de `CurrentUserProvider`. No es autenticación real.

## Ejemplo crear reserva

```json
{
  "eventId": "uuid",
  "items": [{ "ticketTypeId": "uuid", "quantity": 2 }]
}
```

Respuesta inicial: `201 Created`, `reservationId`, `status: HELD`, `expiresAt`. Errores con `application/problem+json` (RFC 9457): `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`.

## Envelope de eventos

```json
{
  "eventId": "uuid",
  "eventType": "BookingCreated",
  "eventVersion": 1,
  "occurredAt": "ISO-8601 UTC",
  "aggregateId": "uuid",
  "correlationId": "uuid",
  "payload": {}
}
```

Usar nombres estables, UTC, versionado explícito y payloads mínimos. No incluir datos sensibles ni datos de tarjeta.

## Eventos MVP

| Evento | Productor | Consumidor |
|---|---|---|
| `EventPublished` | Catalog | Booking (crear inventario) |
| `PaymentRequested` | Booking | Payment |
| `PaymentAuthorized` | Payment | Booking |
| `PaymentRejected` | Payment | Booking |
| `PaymentRefundRequested` | Booking | Payment (compensación de pago tardío) |
| `BookingConfirmed` | Booking | Notification |
| `BookingExpired` | Booking | Notification |
| `BookingCancelled` | Booking | Notification |
| `BookingPaymentFailed` | Booking | Notification |

`PaymentRefundRequested` y `BookingPaymentFailed` se añadieron en la Fase 4 (ADR 0010): el primero
compensa una autorización que llega cuando la reserva ya no se puede confirmar; el segundo informa
al usuario de un pago rechazado. `BookingCreated` es opcional para analytics; no implementar
Analytics Service en MVP.

## RabbitMQ

- Topic exchange durable: `eventflow.events`.
- Colas durables por consumidor: `booking.event-published`, `payment.payment-requested`, `booking.payment-result`, `notification.booking-events`.
- Routing keys por tipo: `event.published`, `payment.requested`, `payment.authorized`, `payment.rejected`, `booking.confirmed`, etc.
- DLQ por cola de negocio. Reintentos acotados con backoff y colas de retry; al agotar intentos, DLQ y alerta/log estructurado.
- ACK solo después de completar la transacción de consumidor. No hacer `requeue=true` infinito.
- Configurar publisher confirms y persistencia de mensajes/colas. Documentar la política de retención y replay.

No prometer exactly-once. Objetivo: entrega al menos una vez + Outbox + consumidor idempotente.
