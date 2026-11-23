-- Isolates each Notification integration test. Notification owns these two tables; no other
-- service may read or write them.
truncate table notification, processed_message;
