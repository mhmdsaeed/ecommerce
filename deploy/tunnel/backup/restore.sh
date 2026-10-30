#!/bin/sh
# Puts one backup back, replacing the whole database and every image (deploy/tunnel/README.md, "Backups"):
#   docker compose stop app minio
#   docker compose exec backup sh /scripts/restore.sh 2026-10-29_0330
#   docker compose start minio app
set -eu

stamp=${1:?Which backup? Give its date and time as in the file names, e.g. 2026-10-29_0330}
db=/backups/ecommerce-$stamp.dump
media=/backups/media-$stamp.tar.gz
[ -f "$db" ] || { echo "No $db" >&2; exit 1; }
[ -f "$media" ] || { echo "No $media" >&2; exit 1; }

# A stopped container's name no longer resolves, so this tells whether the app and MinIO are still running.
for service in app minio; do
    if getent hosts "$service" > /dev/null; then
        echo "Stop the app and MinIO first: docker compose stop app minio" >&2
        exit 1
    fi
done

pg_restore -l "$db" > /dev/null

# Other sessions (pgAdmin) would keep the database from being dropped.
psql -h postgres -U postgres -d postgres -v ON_ERROR_STOP=1 -q \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'ecommerce' AND pid <> pg_backend_pid()" \
    -c "DROP DATABASE IF EXISTS ecommerce" > /dev/null
# --create recreates the database with its owner and connection grants, as the dump recorded them.
pg_restore -h postgres -U postgres -d postgres --create --exit-on-error "$db"

find /media-data -mindepth 1 -delete
tar xzf "$media" -C /media-data

echo "Restored $stamp. Start them again: docker compose start minio app"
