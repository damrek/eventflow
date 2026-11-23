-- Phase 3 messaging tables, owned exclusively by the Booking service.
-- outbox_event is the transactional outbox of the events Booking produces; processed_message is
-- the inbox that makes at-least-once delivery produce exactly one effect per consumer and event.

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

create index ix_outbox_event_pending on outbox_event (occurred_at) where published_at is null;

create table processed_message (
    consumer_name varchar(100) not null,
    event_id      uuid         not null,
    processed_at  timestamptz  not null,
    constraint uk_processed_message_consumer_event unique (consumer_name, event_id)
);
