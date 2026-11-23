-- Isolates each Booking integration test. Booking owns these tables; no other service may read
-- or write them.
truncate table outbox_event, processed_message, reservation_item, reservation, event_inventory,
    idempotency_request;
