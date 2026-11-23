-- Payment schema, owned exclusively by the Payment service.
-- One logical payment per reservation: the unique constraint is the arbiter when two payment
-- requests for the same reservation race. The amount is what Booking asked to authorize; no
-- card data is ever stored.

create table payment (
    id                 uuid           primary key,
    reservation_id     uuid           not null,
    event_id           uuid           not null,
    user_id            varchar(100)   not null,
    amount             numeric(12, 2) not null,
    currency           varchar(3)     not null,
    status             varchar(20)    not null,
    provider_reference varchar(120),
    rejection_reason   varchar(200),
    created_at         timestamptz    not null,
    updated_at         timestamptz    not null,
    version            bigint         not null,
    constraint uk_payment_reservation unique (reservation_id),
    constraint ck_payment_status check (status in ('PENDING', 'AUTHORIZED', 'REJECTED', 'REFUNDED')),
    constraint ck_payment_amount_positive check (amount > 0),
    constraint ck_payment_currency check (currency ~ '^[A-Z]{3}$')
);

create table payment_attempt (
    id                 uuid         primary key,
    payment_id         uuid         not null references payment (id) on delete cascade,
    attempt_number     integer      not null,
    outcome            varchar(20)  not null,
    provider_reference varchar(120),
    latency_ms         bigint       not null,
    error              varchar(200),
    created_at         timestamptz  not null,
    constraint uk_payment_attempt_number unique (payment_id, attempt_number),
    constraint ck_payment_attempt_outcome check (outcome in ('AUTHORIZED', 'REJECTED'))
);
