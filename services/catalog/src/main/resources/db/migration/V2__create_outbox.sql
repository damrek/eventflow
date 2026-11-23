-- Transactional Outbox for events published by the Catalog service. The row is written in the
-- same transaction as the business change; the relay publishes it and sets published_at only
-- after the broker confirms.

create table outbox_event (
    id           uuid         primary key,
    event_id     uuid         not null,
    aggregate_id uuid         not null,
    event_type   varchar(60)  not null,
    routing_key  varchar(100) not null,
    body         text         not null,
    occurred_at  timestamptz  not null,
    published_at timestamptz,
    constraint uk_outbox_event_event unique (event_id)
);

-- The relay only ever scans pending rows in occurrence order.
create index ix_outbox_event_pending on outbox_event (occurred_at) where published_at is null;
