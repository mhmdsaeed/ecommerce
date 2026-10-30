#!/bin/sh
# Runs backup.sh every day at BACKUP_TIME, in the time zone TZ.
set -eu

echo "Backups at $BACKUP_TIME ($TZ) every day, kept $KEEP_DAYS days"
while true; do
    now=$(date +%s)
    next=$(date -d "today $BACKUP_TIME" +%s)
    [ "$next" -gt "$now" ] || next=$(date -d "tomorrow $BACKUP_TIME" +%s)
    sleep $((next - now))
    sh /scripts/backup.sh || echo "Backup failed" >&2
done
