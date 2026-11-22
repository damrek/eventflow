# 04 — Frontend y Redis

## Frontend

SPA: React + TypeScript + Vite + Tailwind CSS. Elegir versiones estables compatibles y fijarlas en lockfile. Librerías: TanStack Query (estado remoto), React Hook Form + Zod (formularios), router ligero. No añadir Redux para duplicar la caché de servidor.

Pantallas MVP:
1. Catálogo y detalle de evento.
2. Selección de tipos/cantidad de entradas.
3. Sala de espera: posición y estado de admisión.
4. Resumen y cuenta atrás de reserva.
5. Estado de pago.
6. Mis reservas y notificaciones.

Separar `api/`, `features/`, `components/`, `hooks/` y `routes/`. Los estados de reserva deben reflejar estados del backend; no simular confirmaciones en cliente. Gestionar loading, empty, error y expiración. UI responsive y accesible; priorizar claridad frente a animaciones.

## Redis: usos previstos

### 1. Virtual Waiting Room — Sorted Set

Una estructura por evento: `waiting:{eventId}`. Insertar usuario una sola vez con score monotónico/orden de entrada; consultar posición con `ZRANK`. Un dispatcher admite usuarios por lotes cuando hay capacidad, emitiendo token temporal con TTL. Las operaciones de admisión deben ser atómicas mediante Lua o transacción Redis.

Definir reglas de reentrada, token expirado y limpieza de miembros. No confiar en el orden de reloj del cliente. Para una primera versión simple puede asignarse secuencia en servidor.

### 2. Rate limiting distribuido

Aplicar límite por usuario/IP a join y create-reservation. Implementar ventana fija con Lua para MVP; token bucket es ampliación. La clave debe incluir endpoint y sujeto, tener TTL y responder `429` con `Retry-After`. El límite se comparte entre todas las réplicas.

### 3. Caché de catálogo

Cache-aside de eventos/detalles con TTL corto. Invalidar al publicar/actualizar evento o aceptar expiración corta como consistencia eventual. Nunca cachear como verdad el stock que autoriza una reserva.

## Límites y seguridad

- PostgreSQL decide disponibilidad, retención, venta y expiración.
- Redis es efímero: prever caída, reinicio y pérdida de claves sin corromper reservas.
- Token de admisión no sustituye a la validación transaccional del inventario.
- No usar Redis Locks para bloquear compra/stock: PostgreSQL resuelve esa invariancia. Locks para tareas auxiliares quedan fuera del MVP.
- No introducir Redis Streams: RabbitMQ es el único broker de eventos.

## Criterios de aceptación

- Un usuario puede unirse, ver su posición y ser admitido.
- Token expirado o inválido no permite saltarse la espera.
- El rate limit funciona con dos instancias de Booking.
- La app sigue siendo correcta si Redis se reinicia: quizá se pierde la cola, pero nunca se duplica o vende inventario por ello.
