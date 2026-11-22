-- Catalog schema, owned exclusively by the Catalog service.
-- Capacity rules: a venue has a physical capacity, an event cannot sell more tickets than it.

create table venue (
    id       uuid         primary key,
    name     varchar(200) not null,
    address  varchar(400) not null,
    capacity integer      not null,
    constraint ck_venue_capacity_positive check (capacity > 0)
);

create table event (
    id        uuid         primary key,
    venue_id  uuid         not null references venue (id),
    name      varchar(200) not null,
    starts_at timestamptz  not null,
    status    varchar(20)  not null,
    constraint ck_event_status check (status in ('DRAFT', 'PUBLISHED'))
);

-- Public listing filters by status and start date window.
create index ix_event_status_starts_at on event (status, starts_at);

create table ticket_type (
    id               uuid           primary key,
    event_id         uuid           not null references event (id) on delete cascade,
    name             varchar(100)   not null,
    price            numeric(12, 2) not null,
    initial_capacity integer        not null,
    constraint ck_ticket_type_price_non_negative check (price >= 0),
    constraint ck_ticket_type_capacity_positive check (initial_capacity > 0),
    constraint uk_ticket_type_event_name unique (event_id, name)
);
