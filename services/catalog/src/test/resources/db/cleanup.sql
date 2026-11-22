-- Isolates each integration test. Catalog owns these three tables; later phases add their
-- own databases and must not reuse this script across services.
truncate table ticket_type, event, venue;
