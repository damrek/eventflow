-- Phase 7: the W3C traceparent of the span that wrote the event.
-- The relay publishes in another thread, seconds after the transaction that wrote the row, so the
-- value travels in the row; without it the broker hop would start an unrelated trace and the
-- reservation flow could not be followed end to end. Nullable on purpose: a scheduled job may have
-- no parent span to continue.
alter table outbox_event add column traceparent varchar(100);
