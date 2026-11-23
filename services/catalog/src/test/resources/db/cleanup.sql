-- Isolates each integration test. Catalog owns these tables; later phases add their own
-- databases and must not reuse this script across services.
truncate table outbox_event, ticket_type, event, venue;
