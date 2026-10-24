#!/usr/bin/env bash
# Isolated real MCP HTTP ALLOW test. No existing deploy database, ports or volumes.
set -euo pipefail
IMAGE="$(printenv DG_MCP_GATEWAY_IMAGE || echo deploy-gateway:latest)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"; chmod 700 "$TMP"
PG="dg-mcp-allow-pg-$RANDOM"; GW="dg-mcp-allow-gw-$RANDOM"
cleanup(){ docker rm -f "$GW" >/dev/null 2>&1 || true;docker rm -f "$PG" >/dev/null 2>&1 || true;rm -rf "$TMP"; }
trap cleanup EXIT
docker image inspect "$IMAGE" >/dev/null
docker run --rm -d --name "$PG" --network none -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17 >/dev/null
ready=0
for n in $(seq 1 45);do if docker exec -u postgres "$PG" pg_isready -U postgres >/dev/null 2>&1;then ready=1;break;fi;sleep 1;done
test "$ready" = 1
docker exec -u postgres "$PG" createdb dataguard
docker exec -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c 'CREATE SCHEMA platform' >/dev/null
docker exec -u postgres "$PG" sh -c 'cd "$PGDATA" && openssl req -x509 -nodes -days 1 -newkey rsa:2048 -keyout server.key -out server.crt -subj /CN=localhost -addext subjectAltName=DNS:localhost >/dev/null 2>&1 && chmod 600 server.key'
docker exec -u postgres "$PG" psql -U postgres -d dataguard -c "ALTER SYSTEM SET ssl='on'" >/dev/null
docker exec -u postgres "$PG" pg_ctl -D /var/lib/postgresql/data reload >/dev/null
docker exec -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c 'CREATE ROLE dg_tls_reader LOGIN; CREATE TABLE public.readonly_probe(id int PRIMARY KEY,amount int); INSERT INTO public.readonly_probe VALUES(1,123),(2,456); GRANT SELECT ON public.readonly_probe TO dg_tls_reader' >/dev/null
docker cp "$PG:/var/lib/postgresql/data/server.crt" "$TMP/root.crt" >/dev/null
chmod 644 "$TMP/root.crt"
python3 - "$TMP" <<'PY'
import pathlib,sys,secrets,hashlib,uuid,json,base64
p=pathlib.Path(sys.argv[1]);token='dg_'+base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
ids=[str(uuid.uuid4()) for _ in range(4)]
(p/'token').write_text(token);(p/'ids').write_text(' '.join(ids)+' '+hashlib.sha256(token.encode()).hexdigest()+'\n')
for name,n,method,params in [
 ('init',1,'initialize',{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'isolated-agent','version':'1'}}),
 ('list',2,'tools/list',{}),
 ('allow',3,'tools/call',{'name':'execute_readonly','arguments':{'datasourceId':ids[3],'database':'dataguard','sql':'SELECT id,amount FROM readonly_probe LIMIT 2','parameters':[],'pageSize':2}}),
 ('attack',4,'tools/call',{'name':'execute_readonly','arguments':{'datasourceId':ids[3],'database':'dataguard','sql':'UPDATE readonly_probe SET amount=999 WHERE id=1','parameters':[],'pageSize':1}})
]:
 (p/(name+'.json')).write_text(json.dumps({'jsonrpc':'2.0','id':n,'method':method,'params':params}))
PY
chmod 600 "$TMP/token" "$TMP/ids"
read -r ORG OWNER AGENT SOURCE HASH < "$TMP/ids"
docker run --rm -d --name "$GW" --network "container:$PG" \
 -e JAVA_TOOL_OPTIONS='-Duser.home=/tmp/read-tls-home' \
 -e GATEWAY_BIND_ADDRESS=0.0.0.0 -e GATEWAY_PORT=8080 \
 -e PLATFORM_JDBC_URL='jdbc:postgresql://localhost:5432/dataguard?currentSchema=platform' \
 -e PG_PLATFORM_USER=postgres -e PG_PLATFORM_PASSWORD=test-only \
 -e DG_ENABLE_READ_EXECUTION=true -e DG_ENABLE_WRITE_EXECUTION=false -e DG_WRITE_QUEUE_ENABLED=false \
 -e DG_CONNECT_TEST_ALLOW_HOSTS=localhost \
 -e DG_TARGET_E2E_READER_USER=dg_tls_reader -e DG_TARGET_E2E_READER_PASSWORD=test-only \
 -v "$TMP/root.crt:/tmp/read-tls-home/.postgresql/root.crt:ro" \
 -v "$TMP:/fixture:ro" "$IMAGE" >/dev/null
up=0
for n in $(seq 1 50);do
 c=$(docker exec -u 0 "$GW" curl -sS --max-time 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health 2>/dev/null||true)
 if test "$c" = 200;then up=1;break;fi;sleep 1
done
if [ "$up" -ne 1 ];then echo 'Isolated gateway failed health';docker logs --tail 14 "$GW" 2>&1 |grep -E 'ERROR|Exception|Started'|tail -10||true;exit 2;fi
echo 'PASSED isolated read-enabled gateway / write-disabled'
docker exec -i -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 >/dev/null <<SQL
SET search_path=platform,public;
INSERT INTO dg_organization(id,code,name) VALUES('$ORG','httpread','Isolated read');
INSERT INTO dg_user(id,org_id,username,display_name) VALUES('$OWNER','$ORG','readerowner','Reader Owner');
INSERT INTO dg_agent(id,org_id,owner_user_id,code,name,max_rows,max_result_bytes,max_execution_ms) VALUES('$AGENT','$ORG','$OWNER','reader','Reader',2,1024,7000);
INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,environment,host,port,database_name,status)
VALUES('$SOURCE','$ORG','$OWNER','readerdb','Reader database','POSTGRESQL','TEST','localhost',5432,'dataguard','ACTIVE');
INSERT INTO dg_datasource_secret(id,org_id,datasource_id,purpose,provider,secret_ref)
VALUES(gen_random_uuid(),'$ORG','$SOURCE','READ','ENV','DG_TARGET_E2E_READER');
INSERT INTO dg_datasource_capability(org_id,datasource_id,capability,support_status,plugin_version,certified_server_version,evidence_ref,certified_at)
VALUES('$ORG','$SOURCE','READ_ONLY_EXECUTION','CERTIFIED','isolated','PostgreSQL 17','test:tls:readonly',now());
INSERT INTO dg_agent_permission(id,org_id,agent_id,datasource_id,tool_name,object_pattern,action,granted_by)
VALUES(gen_random_uuid(),'$ORG','$AGENT','$SOURCE','execute_readonly','readonly_probe','ALLOW','$OWNER');
INSERT INTO dg_object_grant(id,org_id,agent_id,datasource_id,schema_pattern,table_pattern,column_pattern,operations,decision)
VALUES(gen_random_uuid(),'$ORG','$AGENT','$SOURCE','*','readonly_probe','id',ARRAY['SELECT'],'ALLOW'),
(gen_random_uuid(),'$ORG','$AGENT','$SOURCE','*','readonly_probe','amount',ARRAY['SELECT'],'ALLOW');
INSERT INTO dg_native_agent_token(id,org_id,agent_id,token_digest,expires_at)
VALUES(gen_random_uuid(),'$ORG','$AGENT','$HASH',now()+interval '1 hour');
SQL
unset HASH
mcp(){
 docker exec -u 0 -e CASE_NAME="$1" -e REQUEST_NAME="$2" "$GW" bash -lc '
 token=$(cat /fixture/token);sess=$(cat /fixture/session 2>/dev/null||true)
 curl -sS --max-time 8 -D "/tmp/$CASE_NAME.headers" -o "/tmp/$CASE_NAME.body" -w "%{http_code}" \
 -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
 -H "MCP-Protocol-Version: 2025-03-26" -H "Authorization: Bearer $token" \
 -H "Mcp-Session-Id: $sess" -d @"/fixture/$REQUEST_NAME.json" \
 http://127.0.0.1:8080/mcp || true'
}
extract(){ docker exec -u 0 "$GW" cat "/tmp/$1.body"; }
anon=$(docker exec -u 0 "$GW" curl -sS --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/mcp)
test "$anon" = 401
test "$(mcp initialize init)" = 200
docker exec -u 0 "$GW" awk 'tolower($1)=="mcp-session-id:"{gsub("\r","",$2);print $2}' /tmp/initialize.headers | tail -1 > "$TMP/session"
chmod 600 "$TMP/session"
test "$(mcp listing list)" = 200
extract listing |grep -q execute_readonly
echo 'PASSED authenticated MCP protocol and tool discovery'
test "$(mcp authorized allow)" = 200
extract authorized |python3 -c '
import sys,json
s=sys.stdin.read();d=[json.loads(v[5:].strip()) for v in s.splitlines() if v.startswith("data:")]
if not d:d=[json.loads(s)]
r=next(x.get("result",{}) for x in d if x.get("id")==3)
v=json.dumps(r)
assert "ALLOW" in v and "REDACTED" in v and "rowCount" in v,v[:250]
assert "123" not in v and "456" not in v
'
echo 'PASSED authorized SELECT returns masked two-row result over MCP HTTP'
query(){ docker exec -u postgres "$PG" psql -U postgres -d dataguard -Atqc "$1"; }
test "$(query "SELECT count(*) FROM platform.dg_sql_request WHERE org_id='$ORG' AND agent_id='$AGENT' AND request_status='READ_DONE'")" = 1
test "$(query "SELECT count(*) FROM platform.dg_sql_read_summary s JOIN platform.dg_sql_request r ON r.org_id=s.org_id AND r.id=s.request_id WHERE r.org_id='$ORG' AND s.returned_rows=2")" = 1
test "$(query "SELECT count(*) FROM platform.dg_audit_event WHERE org_id='$ORG' AND event_type='SQL_READ_DONE'")" = 1
echo 'PASSED durable read receipt, P95 latency row and tamper-evident audit'
test "$(mcp malicious attack)" = 200
extract malicious |grep -q UNSUPPORTED_SQL_SHAPE
test "$(query 'SELECT amount FROM public.readonly_probe WHERE id=1')" = 123
echo 'PASSED malicious UPDATE denied without modifying database'
docker exec -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c "DELETE FROM platform.dg_object_grant WHERE org_id='$ORG' AND column_pattern='amount'" >/dev/null
test "$(mcp column-denied allow)" = 200
extract column-denied |grep -q COLUMN_NOT_AUTHORIZED
echo 'PASSED revoked column permission denies next MCP call'
docker exec -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c "UPDATE platform.dg_native_agent_token SET revoked_at=now() WHERE org_id='$ORG'" >/dev/null
test "$(mcp token-revoked list)" = 401
echo 'PASSED revoked Agent token receives HTTP 401'
echo REAL_MCP_AUTHORIZED_HTTP_READ_PASS
