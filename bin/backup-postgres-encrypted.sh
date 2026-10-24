#!/usr/bin/env bash
# Stream a PostgreSQL custom dump to a pre-provisioned GPG public recipient.
# Never write a plaintext dump to the host filesystem.
set -euo pipefail
umask 077
: "${DG_BACKUP_RECIPIENT:?set full GPG public key fingerprint}"
: "${DG_BACKUP_DIRECTORY:?set an existing private backup directory}"
CONTAINER="${DG_BACKUP_CONTAINER:-deploy-postgres-1}"
DB="${DG_BACKUP_DATABASE:-dataguard}"
USER="${DG_BACKUP_DB_USER:-dataguard_bootstrap}"
[[ "$CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,100}$ ]] ||
  { echo "Invalid PostgreSQL container" >&2;exit 2; }
[[ "$DB" =~ ^[A-Za-z0-9_]{1,63}$ && "$USER" =~ ^[A-Za-z0-9_]{1,63}$ ]] ||
  { echo "Invalid database or user" >&2;exit 2; }
[[ "$DG_BACKUP_RECIPIENT" =~ ^[A-Fa-f0-9]{40,64}$ ]] ||
  { echo "Expected a full GPG key fingerprint" >&2;exit 2; }
for binary in docker gpg sha256sum flock stat;do
 command -v "$binary" >/dev/null || { echo "Missing required binary: $binary" >&2;exit 2; }
done
[[ -d "$DG_BACKUP_DIRECTORY" && ! -L "$DG_BACKUP_DIRECTORY" ]] ||
 { echo "Backup destination must be an existing real directory" >&2;exit 2; }
[[ "$(stat -c '%a' "$DG_BACKUP_DIRECTORY")" = 700 ]] ||
 { echo "Backup directory must be mode 0700" >&2;exit 2; }
[[ "$(docker inspect "$CONTAINER" --format '{{.State.Running}}' 2>/dev/null)" = true ]] ||
 { echo "PostgreSQL container is not running" >&2;exit 2; }
gpg --batch --list-keys "$DG_BACKUP_RECIPIENT" >/dev/null 2>&1 ||
 { echo "Recipient public key not installed" >&2;exit 2; }
exec 9>"$DG_BACKUP_DIRECTORY/.backup.lock"
flock -n 9 || { echo "Another backup is in progress" >&2;exit 2; }
STAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
BASE="dataguard-$DB-$STAMP-$$.dump.gpg"
OUT="$DG_BACKUP_DIRECTORY/$BASE"
TMP="$OUT.part"
MANIFEST="$OUT.sha256"
cleanup(){ rm -f -- "$TMP"; }
trap cleanup EXIT
docker exec -u postgres "$CONTAINER" pg_dump -U "$USER" -d "$DB" \
 -Fc --no-owner --no-acl |
 gpg --batch --yes --no-tty --trust-model always \
 --encrypt --recipient "$DG_BACKUP_RECIPIENT" --output "$TMP"
test -s "$TMP"
chmod 600 "$TMP"
mv -n "$TMP" "$OUT"
(cd "$DG_BACKUP_DIRECTORY" && sha256sum "$BASE" > "${BASE}.sha256")
chmod 600 "$MANIFEST"
echo "ENCRYPTED_DATABASE_BACKUP_CREATED=$OUT"
echo "BACKUP_SIZE_BYTES=$(stat -c '%s' "$OUT")"
echo "CHECKSUM_FILE=$MANIFEST"
echo "NOTE: Logical backup excludes cluster globals; WAL/PITR requires separate plan."
