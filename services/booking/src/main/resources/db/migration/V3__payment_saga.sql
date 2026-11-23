-- Phase 4: the payment step of the booking saga.
-- The status CHECK widens with the states the payment result can produce, and payment_id records
-- which payment settled the reservation so a late authorization can be refunded exactly once.

alter table reservation drop constraint ck_reservation_status;

alter table reservation add constraint ck_reservation_status check (
    status in ('HELD', 'PENDING_PAYMENT', 'CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED', 'CANCELLED'));

alter table reservation add column payment_id uuid;

create index ix_reservation_payment on reservation (payment_id) where payment_id is not null;
