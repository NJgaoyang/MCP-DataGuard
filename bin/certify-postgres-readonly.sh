#!/usr/bin/env bash
# Read-only physical PostgreSQL account certification.
# Approval flag, CA-authenticated TLS, and least-privilege role are mandatory.
# No DML, secret output, or business data in stdout.
set -euo pipefail
umask 077
[[ "$(printenv DG_CERT_APPROVED_TARGET || true)" == yes ]] ||
 { echo "CERTIFICATION_NO_GO: explicit target authorization required" >&2;exit 2; }
for var in DG_CERT_HOST DG_CERT_PORT DG_CERT_USER DG_CERT_DATABASE \
 DG_CERT_SCHEMA DG_CERT_TABLE PGSSLROOTCERT PGPASSFILE PGSSLMODE;do
 [[ -n "$(printenv "$var" || true)" ]] ||
  { echo "CERTIFICATION_NO_GO: $var missing" >&2;exit 2; }
done
[[ "$PGSSLMODE" == verify-full ]] ||
 { echo "CERTIFICATION_NO_GO: TLS verify-full mandatory" >&2;exit 2; }
[[ "$DG_CERT_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$ &&
   "$DG_CERT_PORT" =~ ^[0-9]{1,5}$ ]] ||
 { echo "CERTIFICATION_NO_GO: unsafe target" >&2;exit 2; }
(( 10#$DG_CERT_PORT>=1 && 10#$DG_CERT_PORT<=65535 )) ||
 { echo "CERTIFICATION_NO_GO: invalid port" >&2;exit 2; }
for var in DG_CERT_USER DG_CERT_DATABASE DG_CERT_SCHEMA DG_CERT_TABLE;do
 value="$(printenv "$var")"
 [[ "$value" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] ||
  { echo "CERTIFICATION_NO_GO: invalid SQL identifier" >&2;exit 2; }
done
for path in "$PGPASSFILE" "$PGSSLROOTCERT";do
 [[ "$path" == /* && -f "$path" && ! -L "$path" ]] ||
  { echo "CERTIFICATION_NO_GO: unsafe secret/CA file" >&2;exit 2; }
done
[[ "$(stat -c '%a' "$PGPASSFILE")" == 600 ]] ||
 { echo "CERTIFICATION_NO_GO: credential file must be 0600" >&2;exit 2; }
command -v psql >/dev/null ||
 { echo "CERTIFICATION_NO_GO: PostgreSQL client missing" >&2;exit 2; }
export PGCONNECT_TIMEOUT=7
query(){
 local result
 if ! result="$(psql -X -A -t -q -v ON_ERROR_STOP=1 \
    -h "$DG_CERT_HOST" -p "$DG_CERT_PORT" -U "$DG_CERT_USER" \
    -d "$DG_CERT_DATABASE" \
    -c "BEGIN READ ONLY; SET LOCAL statement_timeout='8000ms'; $1; COMMIT" 2>/dev/null)";then
  echo "CERTIFICATION_NO_GO: SSL PostgreSQL query failed" >&2
  return 1
 fi
 printf '%s' "$result"
}
role_ok="$(query "SELECT NOT EXISTS(
 SELECT 1 FROM pg_roles r
 WHERE (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
   AND pg_has_role(current_user,r.oid,'MEMBER')
 ) AND NOT has_database_privilege(current_user,current_database(),'CREATE')
 AND NOT EXISTS(
   SELECT 1 FROM pg_namespace n
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
     AND has_schema_privilege(current_user,n.oid,'CREATE')
 ) AND NOT EXISTS(
   SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
     AND c.relkind IN ('r','p','v','m','f')
     AND (has_table_privilege(current_user,c.oid,'INSERT')
       OR has_table_privilege(current_user,c.oid,'UPDATE')
       OR has_table_privilege(current_user,c.oid,'DELETE')
       OR has_table_privilege(current_user,c.oid,'TRUNCATE')
       OR has_table_privilege(current_user,c.oid,'TRIGGER'))
 ) AND NOT EXISTS(
   SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
     AND c.relkind='S'
     AND (has_sequence_privilege(current_user,c.oid,'UPDATE')
       OR has_sequence_privilege(current_user,c.oid,'USAGE'))
 )")" || exit 1
[[ "$role_ok" == t ]] ||
 { echo "CERTIFICATION_NO_GO: elevated SQL or DDL privileges found" >&2;exit 3; }
tls="$(query "SELECT coalesce((SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),false)")" || exit 1
[[ "$tls" == t ]] ||
 { echo "CERTIFICATION_NO_GO: connection is not TLS" >&2;exit 3; }
rls="$(query "SELECT current_setting('row_security')='on'")" || exit 1
[[ "$rls" == t ]] ||
 { echo "CERTIFICATION_NO_GO: row security disabled" >&2;exit 3; }
# Identifiers have passed a strict unquoted identifier regex.
probe="$(query "SELECT count(*) >= 0 FROM (SELECT 1 FROM \"$DG_CERT_SCHEMA\".\"$DG_CERT_TABLE\" LIMIT 1) t")" || exit 1
[[ "$probe" == t ]] ||
 { echo "CERTIFICATION_NO_GO: SELECT probe failed" >&2;exit 3; }
echo "POSTGRESQL_PHYSICAL_READ_CERTIFICATION_PASS"
echo "NOTE: separate platform masking/tenant/column-ACL approval is still required."
