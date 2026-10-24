#!/usr/bin/env bash
# Strictly isolated restore drill. Never opens ports, mounts deployment volumes,
# or restores to an existing database.
set -euo pipefail
umask 077
: "${DG_RESTORE_VERIFY_ARCHIVE:?absolute path to .dump.gpg backup}"
[[ "$DG_RESTORE_VERIFY_ARCHIVE" = /* && "$DG_RESTORE_VERIFY_ARCHIVE" = *.dump.gpg &&
   -f "$DG_RESTORE_VERIFY_ARCHIVE" && ! -L "$DG_RESTORE_VERIFY_ARCHIVE" ]] ||
 { echo "Expected a regular encrypted archive at an absolute path" >&2; exit 2; }
ARCHIVE="$DG_RESTORE_VERIFY_ARCHIVE"
DIR="$(dirname "$ARCHIVE")"
BASE="$(basename "$ARCHIVE")"
[[ "$(stat -c '%a' "$ARCHIVE")" = 600 ]] ||
 { echo "Backup archive must be private (0600)" >&2;exit 2; }
[[ -f "$ARCHIVE.sha256" ]] || { echo "Checksum manifest missing" >&2;exit 2; }
(cd "$DIR" && sha256sum --check --status "$BASE.sha256") ||
 { echo "Backup integrity checksum mismatch" >&2;exit 3; }
NAME="dg-restore-drill-$RANDOM-$$"
cleanup(){ docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run --rm -d --name "$NAME" --network none \
 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17 >/dev/null
ready=0
for i in $(seq 1 40);do
 if docker exec -u postgres "$NAME" pg_isready -U postgres >/dev/null 2>&1;then ready=1;break;fi
 sleep 1
done
test "$ready" = 1
docker exec -u postgres "$NAME" createdb dataguard_restore_verify
# Decrypt only through a streaming pipe. No decrypted data is stored on disk.
gpg --batch --no-tty --decrypt "$ARCHIVE" 2>/dev/null |
 docker exec -i -u postgres "$NAME" pg_restore --no-owner --no-acl \
 --exit-on-error -U postgres -d dataguard_restore_verify
# Verify the critical platform objects are usable on an entirely fresh PG.
TABLES="$(docker exec -u postgres "$NAME" psql -U postgres \
 -d dataguard_restore_verify -Atqc \
 "SELECT count(*) FROM information_schema.tables
   WHERE table_schema='platform' AND table_name IN
    ('dg_sql_approval','dg_audit_event','dg_execution_task','dg_alert_event')")"
test "$TABLES" = 4
echo "ENCRYPTED_BACKUP_RESTORE_DRILL_PASSED"
echo "Verified 4 critical platform tables in an offline disposable PostgreSQL container."
echo "No live database or volume changed."
