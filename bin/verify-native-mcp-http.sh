#!/usr/bin/env bash
set -euo pipefail
trap 'echo "MCP_HTTP_ACCEPTANCE_ERROR_AT_LINE=$LINENO" >&2' ERR
IMAGE="${DG_MCP_GATEWAY_IMAGE:-deploy-gateway:latest}"
DIR="$(mktemp -d)"
chmod 700 "$DIR"
PG="dg-mcp-test-pg-$RANDOM"; GATE="dg-mcp-test-gw-$RANDOM"
cleanup(){ docker rm -f "$GATE" >/dev/null 2>&1||true;docker rm -f "$PG" >/dev/null 2>&1||true;rm -rf "$DIR"; }
trap cleanup EXIT
docker image inspect "$IMAGE" >/dev/null
docker run --rm -d --name "$PG" --network none -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17 >/dev/null
for i in $(seq 1 40);do docker exec -u postgres "$PG" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1;done
docker exec -u postgres "$PG" createdb dataguard
docker exec -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c 'CREATE SCHEMA platform' >/dev/null
printf '%s' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"DataGuard-E2E","version":"1.0"}}}' >"$DIR/initialize.json"
printf '%s' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' >"$DIR/list.json"
printf '%s' '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"execute_readonly","arguments":{"datasourceId":"00000000-0000-0000-0000-000000000001","database":"dataguard","sql":"SELECT id FROM readonly_probe LIMIT 1","parameters":[],"pageSize":1}}}' >"$DIR/read.json"
python3 - "$DIR" <<'PY'
import sys,uuid,secrets,base64,hashlib,pathlib
p=pathlib.Path(sys.argv[1]);token="dg_"+base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")
(p/"token").write_text(token)
(p/"ids").write_text(" ".join(str(uuid.uuid4()) for _ in range(3))+" "+hashlib.sha256(token.encode()).hexdigest())
PY
chmod 600 "$DIR/token" "$DIR/ids"
echo >> "$DIR/ids"
read -r ORG USER AGENT DIGEST <"$DIR/ids"
docker run --rm -d --name "$GATE" --network "container:$PG" \
 -e GATEWAY_BIND_ADDRESS=0.0.0.0 -e GATEWAY_PORT=8080 \
 -e PLATFORM_JDBC_URL='jdbc:postgresql://127.0.0.1:5432/dataguard?currentSchema=platform' \
 -e PG_PLATFORM_USER=postgres -e PG_PLATFORM_PASSWORD=test-only \
 -e DG_ENABLE_READ_EXECUTION=false -e DG_ENABLE_WRITE_EXECUTION=false -e DG_WRITE_QUEUE_ENABLED=false \
 -v "$DIR:/fixture" "$IMAGE" >/dev/null
up=0
for i in $(seq 1 50);do
 status=$(docker exec -u 0 "$GATE" curl -sS --max-time 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health 2>/dev/null||true)
 if test "$status" = 200;then up=1;break;fi;sleep 1
done
if test "$up" != 1;then docker logs --tail 22 "$GATE" | sed -E 's/(password|secret|token)=[^ ]+/\1=[REDACTED]/gi' >&2;exit 3;fi
echo 'PASSED: isolated gateway startup, all execution flags off'
docker exec -i -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 >/dev/null <<SQL
SET search_path TO platform,public;
INSERT INTO dg_organization(id,code,name) VALUES('$ORG','mcp_http','Protocol fixture');
INSERT INTO dg_user(id,org_id,username,display_name) VALUES('$USER','$ORG','owner','Protocol owner');
INSERT INTO dg_agent(id,org_id,owner_user_id,code,name) VALUES('$AGENT','$ORG','$USER','http_agent','HTTP Agent');
INSERT INTO dg_native_agent_token(id,org_id,agent_id,token_digest,expires_at)
VALUES(gen_random_uuid(),'$ORG','$AGENT','$DIGEST',now()+interval '1 hour');
SQL
unset DIGEST
anon=$(docker exec -u 0 "$GATE" curl -sS --max-time 7 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/mcp)
test "$anon" = 401
echo 'PASSED: anonymous MCP blocked'
mcp(){
 docker exec -u 0 -e TEST_PHASE="$1" -e TEST_REQ="$2" "$GATE" bash -lc '
 token=$(cat /fixture/token)
 session=$(cat /fixture/session 2>/dev/null||true)
 curl -sS --max-time 12 -D "/fixture/$TEST_PHASE.headers" -o "/fixture/$TEST_PHASE.body" -w "%{http_code}" \
 -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
 -H "MCP-Protocol-Version: 2025-03-26" -H "Authorization: Bearer $token" \
 -H "Mcp-Session-Id: $session" \
 -d @"/fixture/$TEST_REQ.json" http://127.0.0.1:8080/mcp || true'
}
init=$(mcp initialize initialize);echo "MCP_INIT_HTTP=$init"
test "$init" = 200
grep -Eq 'serverInfo|protocolVersion' "$DIR/initialize.body"
awk 'tolower($1)=="mcp-session-id:" {gsub("\r","",$2);print $2}' "$DIR/initialize.headers" | tail -1 >"$DIR/session"
echo 'PASSED: real Agent token initializes MCP'
list=$(mcp listing list);echo "MCP_LIST_HTTP=$list"
test "$list" = 200
grep -q 'execute_readonly' "$DIR/listing.body"
grep -q 'check_sql' "$DIR/listing.body"
echo 'PASSED: authorized tools/list'
readcode=$(mcp denied-read read);echo "MCP_READ_HTTP=$readcode"
test "$readcode" = 200
grep -q READ_EXECUTION_DISABLED "$DIR/denied-read.body"
echo 'PASSED: read denied with execution disabled'
docker exec -u postgres "$PG" psql -U postgres -d dataguard -v ON_ERROR_STOP=1 -c \
 "UPDATE platform.dg_native_agent_token SET revoked_at=now() WHERE org_id='$ORG' AND agent_id='$AGENT'" >/dev/null
after=$(mcp revoked list);test "$after" = 401
echo 'PASSED: token revocation immediate'
python3 - "$DIR/initialize.body" "$DIR/listing.body" "$DIR/denied-read.body" <<'CHECK'
import sys,json,pathlib
for file,request_id in zip(sys.argv[1:],[1,2,3]):
 text=pathlib.Path(file).read_text()
 messages=[]
 for line in text.splitlines():
  if line.startswith('data:'):
   messages.append(json.loads(line[5:].strip()))
 if not messages:
  messages=[json.loads(text)]
 assert any(isinstance(x,dict) and x.get('id')==request_id and 'result' in x for x in messages),file
print('PASSED: complete JSON-RPC result envelopes for initialize/tools/list/tools/call')
CHECK
echo REAL_NATIVE_MCP_HTTP_ACCEPTANCE_PASS

[executed on device: VM-0-4-tencentos (c1c2e3d4-a7e7-41e3-b2ed-dbf6a4613fad)]
