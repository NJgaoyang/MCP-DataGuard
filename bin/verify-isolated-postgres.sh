#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v docker >/dev/null || { echo "Docker required" >&2; exit 1; }
NAME="dg-e2e-pg-$RANDOM-$RANDOM"
# No published ports, no network namespace, no persistent volume. Trust applies
# ONLY to connections executed inside the disposable container's own namespace.
cleanup(){ docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run --rm -d --name "$NAME" --network none \
  -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17 >/dev/null
ready=0
for _ in $(seq 1 40); do
 if docker exec -u postgres "$NAME" pg_isready -U postgres >/dev/null 2>&1; then
  ready=1; break
 fi
 sleep 1
done
test "$ready" -eq 1 || { echo "Ephemeral PostgreSQL not ready" >&2; exit 1; }
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres \
 -c 'CREATE DATABASE dataguard' >/dev/null
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d dataguard \
 -c 'CREATE SCHEMA platform' >/dev/null
for migration in "$ROOT"/dataguard-server/src/main/resources/db/migration/V*.sql; do
 docker exec -i -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres \
   -d dataguard -c 'SET search_path TO platform,public' -f /dev/stdin \
   < "$migration" >/dev/null
 echo "PASSED migration: $(basename "$migration")"
done
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d dataguard \
 -c 'CREATE ROLE dg_test_reader LOGIN; CREATE TABLE platform.readonly_probe(id bigint PRIMARY KEY, amount int); INSERT INTO platform.readonly_probe VALUES(1,123); GRANT USAGE ON SCHEMA platform TO dg_test_reader; GRANT SELECT ON platform.readonly_probe TO dg_test_reader;' >/dev/null
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U dg_test_reader -d dataguard \
 -Atqc 'SELECT amount FROM platform.readonly_probe WHERE id=1' | grep -qx '123'
if docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U dg_test_reader -d dataguard \
 -c 'UPDATE platform.readonly_probe SET amount=999 WHERE id=1' >/dev/null 2>&1; then
 echo "FAIL: readonly role updated a row" >&2; exit 1
fi
echo "PASSED: readonly role SELECT allowed, UPDATE denied"
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d dataguard \
 -Atqc 'BEGIN READ ONLY; SHOW transaction_read_only; ROLLBACK' | grep -qx 'on'
echo "PASSED: PostgreSQL readonly transaction"
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d dataguard \
 -Atqc "SELECT count(*) FROM information_schema.tables WHERE table_schema='platform' AND table_name IN ('dg_sql_approval','dg_outbox_event','dg_audit_event','dg_sql_request')" | grep -qx '4'
echo "PASSED: approval, outbox, audit and SQL request schema"
echo "ISOLATED_POSTGRES_VERIFICATION_PASSED"
