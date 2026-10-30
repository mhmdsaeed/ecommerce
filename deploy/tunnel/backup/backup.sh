#!/bin/sh
# One backup into /backups: the database (pg_dump's custom format) and the images (MinIO's data folder), then
# deletes backups older than KEEP_DAYS days. run.sh calls it every night; by hand:
#   docker compose exec backup sh /scripts/backup.sh
set -eu

stamp=$(date +%Y-%m-%d_%H%M)
cd /backups

# Written under a temporary name and renamed once complete, so a half-written file never looks like a backup.
pg_dump -h postgres -U postgres -Fc -f ".ecommerce-$stamp.dump.part" ecommerce
pg_restore -l ".ecommerce-$stamp.dump.part" > /dev/null
mv ".ecommerce-$stamp.dump.part" "ecommerce-$stamp.dump"

tar czf ".media-$stamp.tar.gz.part" -C /media-data .
mv ".media-$stamp.tar.gz.part" "media-$stamp.tar.gz"

find /backups -maxdepth 1 \( -name 'ecommerce-*.dump' -o -name 'media-*.tar.gz' \) -mtime +"$KEEP_DAYS" -delete
find /backups -maxdepth 1 -name '.*.part' -mmin +60 -delete

echo "Backup $stamp: ecommerce-$stamp.dump ($(du -h "ecommerce-$stamp.dump" | cut -f1)), media-$stamp.tar.gz ($(du -h "media-$stamp.tar.gz" | cut -f1))"
