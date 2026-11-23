-- Isolates each Payment integration test. Payment owns these tables; no other service may read
-- or write them. Deletes instead of TRUNCATE: the consumer keeps working while the test starts,
-- and row-level locks do not deadlock with a delivery that is still finishing.
delete from payment_attempt;
delete from payment;
delete from outbox_event;
delete from processed_message;
