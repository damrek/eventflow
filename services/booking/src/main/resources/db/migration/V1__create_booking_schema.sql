-- Booking schema, owned exclusively by the Booking service.
-- Inventory invariant: capacity = available + held + sold, all of them non-negative.
-- PostgreSQL is the source of truth; Redis never authorizes a hold.

create table event_inventory (
    id             uuid           primary key,
    event_id       uuid           not null,
    ticket_type_id uuid           not null,
    price          numeric(12, 2) not null,
    capacity       integer        not null,
    available      integer        not null,
    held           integer        not null,
    sold           integer        not null,
    constraint uk_event_inventory_event_ticket unique (event_id, ticket_type_id),
    constraint ck_event_inventory_capacity_positive check (capacity > 0),
    constraint ck_event_inventory_counts_non_negative check (available >= 0 and held >= 0 and sold >= 0),
    constraint ck_event_inventory_balance check (available + held + sold = capacity),
    constraint ck_event_inventory_price_non_negative check (price >= 0)
);

create index ix_event_inventory_event on event_inventory (event_id);

create table reservation (
    id         uuid         primary key,
    user_id    varchar(100) not null,
    event_id   uuid         not null,
    status     varchar(20)  not null,
    expires_at timestamptz  not null,
    created_at timestamptz  not null,
    version    bigint       not null,
    constraint ck_reservation_status check (status in ('HELD', 'EXPIRED', 'CANCELLED'))
);

-- The expiry worker scans by status + expires_at; the demo reads a user's reservations.
create index ix_reservation_expiry on reservation (status, expires_at);
create index ix_reservation_user_created on reservation (user_id, created_at desc);

create table reservation_item (
    id             uuid           primary key,
    reservation_id uuid           not null references reservation (id) on delete cascade,
    ticket_type_id uuid           not null,
    quantity       integer        not null,
    unit_price     numeric(12, 2) not null,
    constraint ck_reservation_item_quantity_positive check (quantity > 0),
    constraint ck_reservation_item_price_non_negative check (unit_price >= 0),
    constraint uk_reservation_item_type unique (reservation_id, ticket_type_id)
);

-- One row per (user, Idempotency-Key). response_body is the serialized original response so a
-- replay returns exactly what the first call returned; the unique key is the arbiter between
-- concurrent duplicates (insert ... on conflict do nothing).
create table idempotency_request (
    id              uuid         primary key,
    user_id         varchar(100) not null,
    idempotency_key varchar(200) not null,
    request_hash    varchar(64)  not null,
    response_status integer,
    response_body   text,
    created_at      timestamptz  not null,
    constraint uk_idempotency_request_user_key unique (user_id, idempotency_key)
);
