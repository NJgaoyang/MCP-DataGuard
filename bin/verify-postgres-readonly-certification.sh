#!/usr/bin/env bash
# Isolated physical PostgreSQL 17 role and TLS certification acceptance.
# No existing database volume, network or service is used.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="dg-pg-readcert-$RANDOM-$RANDOM"
cleanup(){ docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker image inspect postgres:17 >/dev/null
docker run --rm -d --name "$NAME" --network none \
 -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=dataguard \
 postgres:17 >/dev/null
ready=0
for i in $(seq 1 45);do
 if docker exec -u postgres "$NAME" psql -X -Atqc 'SELECT 1' -U postgres -d dataguard 2>/dev/null|grep -qx 1;then
  ready=1;break
 fi
 sleep 1
done
[[ "$ready" -eq 1 ]] || { echo "Isolated PostgreSQL never ready" >&2;exit 1; }
docker exec -u postgres "$NAME" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c "
 CREATE ROLE dg_cert_reader LOGIN;
 CREATE TABLE public.readonly_probe(id integer PRIMARY KEY,amount integer);
 INSERT INTO public.readonly_probe VALUES(1,123);
 GRANT SELECT ON public.readonly_probe TO dg_cert_reader;" >/dev/null
docker exec -u postgres "$NAME" sh -c 'cd "$PGDATA" &&
 openssl req -x509 -nodes -days 1 -newkey rsa:2048 -keyout server.key \
 -out server.crt -subj /CN=localhost -addext subjectAltName=DNS:localhost \
 >/dev/null 2>&1 && chmod 600 server.key &&
 cp server.crt /tmp/dg-cert-root.crt &&
 chmod 644 /tmp/dg-cert-root.crt &&
 printf "localhost:5432:dataguard:dg_cert_reader:test-only\n" >/tmp/dg-cert.pgpass &&
 chmod 600 /tmp/dg-cert.pgpass' >/dev/null
docker exec -u postgres "$NAME" psql -U postgres -d dataguard \
 -v ON_ERROR_STOP=1 -c "ALTER SYSTEM SET ssl='on'" >/dev/null
docker exec -u postgres "$NAME" pg_ctl -D /var/lib/postgresql/data reload >/dev/null
docker cp "$ROOT/bin/certify-postgres-readonly.sh" "$NAME:/tmp/dg-certify.sh" >/dev/null
args=(
 -u postgres
 -e DG_CERT_APPROVED_TARGET=yes
 -e DG_CERT_HOST=localhost
 -e DG_CERT_PORT=5432
 -e DG_CERT_USER=dg_cert_reader
 -e DG_CERT_DATABASE=dataguard
 -e DG_CERT_SCHEMA=public
 -e DG_CERT_TABLE=readonly_probe
 -e PGSSLROOTCERT=/tmp/dg-cert-root.crt
 -e PGPASSFILE=/tmp/dg-cert.pgpass
 -e PGSSLMODE=verify-full
)
docker exec "${args[@]}" "$NAME" bash /tmp/dg-certify.sh
echo "PASS: isolated physical SELECT-only role over verified TLS"
if docker exec -u postgres -e DG_CERT_APPROVED_TARGET=no "$NAME" \
 bash /tmp/dg-certify.sh >/dev/null 2>&1;then
 echo "FAIL: missing target authorization accepted" >&2;exit 1
fi
echo "PASS: missing explicit target approval denied"
if docker exec -u postgres -e DG_CERT_APPROVED_TARGET=yes \
 -e DG_CERT_HOST=localhost -e DG_CERT_PORT=5432 \
 -e DG_CERT_USER=dg_cert_reader -e DG_CERT_DATABASE=dataguard \
 -e DG_CERT_SCHEMA=public -e DG_CERT_TABLE=readonly_probe \
 -e PGSSLROOTCERT=/tmp/dg-cert-root.crt \
 -e PGPASSFILE=/tmp/dg-cert.pgpass -e PGSSLMODE=disable \
 "$NAME" bash /tmp/dg-certify.sh >/dev/null 2>&1;then
 echo "FAIL: non-TLS mode accepted" >&2;exit 1
fi
echo "PASS: insecure TLS mode rejected"
docker exec -u postgres "$NAME" psql -U postgres -d dataguard \
 -v ON_ERROR_STOP=1 -c "GRANT UPDATE ON public.readonly_probe TO dg_cert_reader" >/dev/null
if docker exec "${args[@]}" "$NAME" bash /tmp/dg-certify.sh >/dev/null 2>&1;then
 echo "FAIL: UPDATE grant accepted as read-only" >&2;exit 1
fi
echo "PASS: UPDATE privilege invalidates read-only source certification"
echo "POSTGRESQL_READ_CREDENTIAL_ISOLATED_GATE_PASS"
