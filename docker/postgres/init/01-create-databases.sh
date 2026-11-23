#!/bin/sh
# Runs only when the PostgreSQL volume is initialised (a brand-new cluster). The service
# specification keeps one instance with one database per service; POSTGRES_DB creates the
# first one, this script creates the rest. Adding a service later means adding its database
# here, or `docker compose down -v` for an existing local volume.
set -e

for database in booking_db notification_db payment_db; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c "CREATE DATABASE $database"
done
