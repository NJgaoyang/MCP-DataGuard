#!/usr/bin/env bash
# Actual SIGKILL of an isolated physical write client AFTER PostgreSQL COMMIT.
# Model of crash-before-ACK. This is not a production Java Worker chaos test;
# the real platform UNKNOWN/no-retry invariant is separately tested with PG.
set -euo pipefail
docker image inspect postgres:17 >/dev/null
NET="dg-client-crash-net-$$"
DB="dg-client-crash-db-$$"
WORKER="dg-client-crash-worker-$$"
cleanup(){
 docker rm -f "$WORKER" "$DB" >/dev/null 2>&1 || true
 docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create --internal "$NET" >/dev/null
docker run --rm -d --name "$DB" --network "$NET" \
 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17 >/dev/null
for i in $(seq 1 45);do
 if docker exec -u postgres "$DB" pg_isready -U postgres >/dev/null 2>&1;then break;fi
 sleep 1
done
docker exec -u postgres "$DB" pg_isready -U postgres >/dev/null
docker exec -u postgres "$DB" createdb chaos
docker exec -i -u postgres "$DB" psql -U postgres -d chaos -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE TABLE public.write_probe(id int PRIMARY KEY,amount int NOT NULL);
INSERT INTO public.write_probe VALUES(1,100);
CREATE TABLE public.execution_ticket(id int PRIMARY KEY,task_state text,attempt_no int);
INSERT INTO public.execution_ticket VALUES(1,'EXECUTING',1);
CREATE PROCEDURE public.commit_then_wait()
LANGUAGE plpgsql AS $$
BEGIN
 UPDATE public.write_probe SET amount=amount+525 WHERE id=1;
 COMMIT;
 PERFORM pg_sleep(50);
END
$$;
SQL
query(){
 docker exec -u postgres "$DB" psql -U postgres -d chaos -Atqc "$1"
}
docker run -d --name "$WORKER" --network "$NET" \
 --entrypoint sh postgres:17 -c \
 "exec psql -h $DB -U postgres -d chaos -v ON_ERROR_STOP=1 -c 'CALL public.commit_then_wait()'" >/dev/null
durable=0
for i in $(seq 1 100);do
 if [[ "$(query 'SELECT amount FROM public.write_probe WHERE id=1')" == 625 ]];then
  durable=1;break
 fi
 sleep 0.2
done
[[ "$durable" == 1 ]] || { echo "Commit never became visible" >&2;exit 1; }
[[ "$(docker inspect "$WORKER" --format '{{.State.Running}}')" == true ]] ||
 { echo "Client completed before crash injection" >&2;exit 1; }
docker kill --signal=KILL "$WORKER" >/dev/null
[[ "$(docker inspect "$WORKER" --format '{{.State.ExitCode}}')" == 137 ]] ||
 { echo "Did not kill the worker client" >&2;exit 1; }
[[ "$(query 'SELECT amount FROM public.write_probe WHERE id=1')" == 625 ]] ||
 { echo "Durable remote write disappeared after process SIGKILL" >&2;exit 1; }
# Model the platform's fail-closed UNKNOWN transition and one-shot claim
# guard: a restart must not turn an already attempted ticket into QUEUED.
query "UPDATE public.execution_ticket SET task_state='UNKNOWN' WHERE id=1 AND task_state='EXECUTING'" >/dev/null
[[ "$(query "SELECT count(*) FROM public.execution_ticket WHERE task_state='UNKNOWN' AND attempt_no=1")" == 1 ]]
replay="$(query "WITH claimed AS (
  UPDATE public.execution_ticket SET task_state='EXECUTING',attempt_no=attempt_no+1
  WHERE id=1 AND task_state='QUEUED' AND attempt_no=0 RETURNING 1
) SELECT count(*) FROM claimed")"
[[ "$replay" == 0 ]] || { echo "UNKNOWN ticket was replayed" >&2;exit 1; }
[[ "$(query 'SELECT amount FROM public.write_probe WHERE id=1')" == 625 ]]
echo "REAL_SIGKILL_AFTER_PG_COMMIT_PASS"
echo "Confirmed durable target commit, worker-client SIGKILL, and no claim on UNKNOWN ticket."
echo "Limitation: worker is a disposable psql client; full Java Worker SIGKILL remains separate pre-production work."
