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
ROLE_PROBE="SELECT NOT EXISTS(SELECT 1 FROM pg_roles r
 WHERE (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
   AND pg_has_role(current_user,r.oid,'MEMBER'))
 AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace ns ON ns.oid=c.relnamespace
 WHERE ns.nspname NOT LIKE 'pg_%' AND ns.nspname<>'information_schema'
   AND c.relkind IN ('r','p','f','v','m')
   AND (has_table_privilege(current_user,c.oid,'INSERT')
    OR has_table_privilege(current_user,c.oid,'UPDATE')
    OR has_table_privilege(current_user,c.oid,'DELETE')
    OR has_table_privilege(current_user,c.oid,'TRUNCATE')
    OR has_table_privilege(current_user,c.oid,'TRIGGER')))
 AND NOT EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%'
   AND n.nspname<>'information_schema'
   AND has_schema_privilege(current_user,n.oid,'CREATE'))
 AND NOT has_database_privilege(current_user,current_database(),'CREATE')"
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U dg_test_reader -d dataguard \
 -Atqc "$ROLE_PROBE" | grep -qx 't'
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d dataguard \
 -c 'GRANT UPDATE ON platform.readonly_probe TO dg_test_reader' >/dev/null
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U dg_test_reader -d dataguard \
 -Atqc "$ROLE_PROBE" | grep -qx 'f'
echo "PASSED: execution role certification rejects gained UPDATE privilege"
docker exec -u postgres "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d dataguard \
 -Atqc "SELECT count(*) FROM information_schema.tables WHERE table_schema='platform' AND table_name IN ('dg_sql_approval','dg_outbox_event','dg_audit_event','dg_sql_request')" | grep -qx '4'
echo "PASSED: approval, outbox, audit and SQL request schema"
if [[ "${DG_RUN_FLOWABLE_OUTBOX_E2E:-false}" == "true" ]]; then
 CACHE="${DG_M2_CACHE:-$HOME/.cache/mcp-dataguard/m2}"
 mkdir -p "$CACHE"
 docker run --rm --network "container:$NAME" \
   -e DG_OUTBOX_E2E_URL=jdbc:postgresql://127.0.0.1:5432/dataguard \
   -v "$ROOT:/workspace" -v "$CACHE:/root/.m2" -w /workspace \
   maven:3.9.11-eclipse-temurin-21 \
   mvn -B -o -ntp -pl dataguard-workflow -am \
     -Dtest=ApprovalOutboxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
 echo "PASSED: platform approval outbox to two-stage Flowable integration"
fi
echo "ISOLATED_POSTGRES_VERIFICATION_PASSED"
