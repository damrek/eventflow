-- Isolates each Booking integration test. Booking owns these four tables; no other service
-- may read or write them.
truncate table reservation_item, reservation, event_inventory, idempotency_request;
