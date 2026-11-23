#!/bin/sh
# Creates one database per service on a brand-new cluster. PostgreSQL runs this only while the
# volume is initialised, so it is written to be safe to re-run on an existing volume:
#
#   docker compose exec -T postgres sh /docker-entrypoint-initdb.d/01-create-databases.sh
#
# That is the recovery path when a service added later (Notification, Payment) finds its database
# missing. It never drops or rewrites data; `docker compose down -v` is not required.
set -e

for database in catalog_db booking_db notification_db payment_db; do
    exists=$(psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$database'" \
        --username "$POSTGRES_USER" --dbname "$POSTGRES_DB")
    if [ "$exists" != "1" ]; then
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
            -c "CREATE DATABASE $database"
    fi
done
