#!/usr/bin/env bash
# Actual TCP backend disconnection at both sides of PostgreSQL COMMIT.
# This test uses ONLY disposable containers on an isolated Docker network:
# 1. Disconnect during an uncommitted transaction: SQL must roll back.
# 2. The stored procedure COMMITs, then waits before returning a CALL result.
#    Terminate the backend during that wait: the caller gets a broken TCP
#    connection with NO successful command acknowledgement, but data DID commit.
#
# This validates remote outcome ambiguity. Platform UNKNOWN and no-retry are
# separately asserted by ExecutionRecoveryWatchdogIntegrationTest.
set -euo pipefail
for tool in docker grep awk;do command -v "$tool" >/dev/null || exit 2;done
docker image inspect postgres:17 >/dev/null
NET="dg-commit-chaos-net-$$"
DB="dg-commit-chaos-db-$$"
CLIENT="dg-commit-chaos-client-$$"
cleanup(){
 docker rm -f "$CLIENT" "$DB" >/dev/null 2>&1 || true
 docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# Neither published ports nor deployment networks/volumes are permitted.
docker network create --internal "$NET" >/dev/null
docker run --rm -d --name "$DB" --network "$NET" \
 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17 >/dev/null
for i in $(seq 1 45);do
 if docker exec -u postgres "$DB" pg_isready -U postgres >/dev/null 2>&1;then break;fi
 sleep 1
done
docker exec -u postgres "$DB" pg_isready -U postgres >/dev/null
docker exec -u postgres "$DB" createdb chaos
docker exec -i -u postgres "$DB" psql -v ON_ERROR_STOP=1 -U postgres -d chaos >/dev/null <<'SQL'
CREATE TABLE public.chaos_probe(
  id integer PRIMARY KEY,
  amount integer NOT NULL
);
INSERT INTO public.chaos_probe VALUES(1,100);
-- This never reaches COMMIT if the backend is killed during pg_sleep.
CREATE PROCEDURE public.chaos_wait_before_commit()
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE public.chaos_probe SET amount=amount+100 WHERE id=1;
  PERFORM pg_sleep(25);
  COMMIT;
END
$$;
-- PostgreSQL commits *inside* CALL, before pg_sleep returns a result.
-- Killing its socket during sleep loses the CALL response but not the write.
CREATE PROCEDURE public.chaos_commit_then_wait()
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE public.chaos_probe SET amount=amount+525 WHERE id=1;
  COMMIT;
  PERFORM pg_sleep(25);
END
$$;
SQL
docker run --rm -d --name "$CLIENT" --network "$NET" \
 --entrypoint sh postgres:17 -c 'sleep 180' >/dev/null
sql(){
 docker exec -u postgres "$DB" psql -U postgres -d chaos -Atqc "$1"
}
launch(){
 local label="$1" routine="$2"
 docker exec -d "$CLIENT" sh -c \
  "PGAPPNAME=dg_$label psql -h $DB -U postgres -d chaos -v ON_ERROR_STOP=1 -c 'CALL public.$routine()' > /tmp/$label.log 2>&1; echo \$? > /tmp/$label.exit"
}
wait_active(){
 local label="$1" active=""
 for i in $(seq 1 60);do
  active="$(sql "SELECT count(*) FROM pg_stat_activity WHERE application_name='dg_$label' AND state='active'")"
  if [[ "$active" == 1 ]];then return 0;fi
  sleep 0.2
 done
 echo "FAIL: no active PostgreSQL backend for $label" >&2
 return 1
}
kill_backend(){
 local label="$1" count
 count="$(sql "SELECT count(*) FROM pg_stat_activity WHERE application_name='dg_$label' AND state='active'")"
 [[ "$count" == 1 ]] || { echo "Expected exactly one active session" >&2;return 1; }
 local terminated
 terminated="$(sql "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='dg_$label' AND state='active'")"
 [[ "$terminated" == t ]] || { echo "Backend termination failed" >&2;return 1; }
}
verify_broken_connection(){
 local label="$1" status=""
 for i in $(seq 1 45);do
  status="$(docker exec "$CLIENT" sh -c "cat /tmp/$label.exit 2>/dev/null || true")"
  [[ "$status" != "" ]] && break
  sleep 0.2
 done
 [[ "$status" =~ ^[0-9]+$ && "$status" -ne 0 ]] ||
  { echo "FAIL: disconnected client did not report an error ($label)" >&2;return 1; }
 docker exec "$CLIENT" grep -Eqi \
  'server closed the connection|terminating connection|connection to server was lost|SSL SYSCALL|connection failure|connection reset' \
  "/tmp/$label.log" || { echo "Unexpected client result ($label)" >&2;return 1; }
}
launch before_commit chaos_wait_before_commit
wait_active before_commit
kill_backend before_commit
verify_broken_connection before_commit
[[ "$(sql 'SELECT amount FROM public.chaos_probe WHERE id=1')" == 100 ]] ||
 { echo "FAIL: uncommitted transaction should have rolled back" >&2;exit 1; }
echo "PASS: real PostgreSQL TCP disconnect before COMMIT -> transaction rolled back"

launch after_commit chaos_commit_then_wait
wait_active after_commit
# The only authoritative evidence for COMMIT is a *separate* connection
# seeing the committed value while CALL has not yet returned to the client.
committed=0
for i in $(seq 1 45);do
 [[ "$(sql 'SELECT amount FROM public.chaos_probe WHERE id=1')" == 625 ]] &&
   { committed=1;break; }
 sleep 0.2
done
[[ "$committed" == 1 ]] || { echo "FAIL: COMMIT was not externally visible" >&2;exit 1; }
kill_backend after_commit
verify_broken_connection after_commit
[[ "$(sql 'SELECT amount FROM public.chaos_probe WHERE id=1')" == 625 ]] ||
 { echo "FAIL: target write was not durable after lost acknowledgement" >&2;exit 1; }
[[ "$(sql 'SELECT count(*) FROM public.chaos_probe')" == 1 ]] ||
 { echo "FAIL: duplicate physical record" >&2;exit 1; }
echo "PASS: actual PostgreSQL COMMIT durable, then TCP disconnect before CALL acknowledgement"
echo "PASS: no replay performed; durable amount=625 (a duplicate increment would be 1150)"
echo "REAL_POSTGRES_TCP_COMMIT_AMBIGUITY_PASS"
