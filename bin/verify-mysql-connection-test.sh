#!/usr/bin/env bash
# Real MySQL 8.4 data-source/TLS/privilege acceptance. Never enables MCP SQL.
# Usage (environment-specific secret files are root-only and not committed):
# DG_MYSQL_TEST_PASSWORD_FILE=/path/read-password \
# DG_MYSQL_TRUSTSTORE_FILE=/path/test-truststore.p12 \
# DG_MYSQL_TRUSTSTORE_PASSWORD=<test truststore password> bash bin/verify-mysql-connection-test.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "$(printenv DG_MYSQL_TEST_PASSWORD_FILE)"
: "$(printenv DG_MYSQL_TRUSTSTORE_FILE)"
: "$(printenv DG_MYSQL_TRUSTSTORE_PASSWORD)"
test -r "$DG_MYSQL_TEST_PASSWORD_FILE" && test -s "$DG_MYSQL_TEST_PASSWORD_FILE"
test -r "$DG_MYSQL_TRUSTSTORE_FILE" && test -s "$DG_MYSQL_TRUSTSTORE_FILE"
# The synthetic fixture is reachable only at the host loopback.
ss -lnt '( sport = :3307 )' | grep -q '127.0.0.1:3307'
CACHE="$(printenv DG_M2_CACHE || true)"
if [ -z "$CACHE" ];then CACHE="$HOME/.cache/mcp-dataguard/m2";fi
mkdir -p "$CACHE"
umask 077
PW="$(cat "$DG_MYSQL_TEST_PASSWORD_FILE")"
docker run --rm --network host \
 -e DG_MYSQL_TEST_ENABLED=true -e DG_TARGET_MYSQL_E2E_USER=dg_probe_ro \
 -e DG_TARGET_MYSQL_E2E_PASSWORD="$PW" \
 -e JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/mysqltest/truststore.p12 -Djavax.net.ssl.trustStorePassword=$DG_MYSQL_TRUSTSTORE_PASSWORD -Djavax.net.ssl.trustStoreType=PKCS12" \
 -v "$ROOT:/workspace" -v "$CACHE:/root/.m2" \
 -v "$DG_MYSQL_TRUSTSTORE_FILE:/mysqltest/truststore.p12:ro" \
 -w /workspace maven:3.9.11-eclipse-temurin-21 \
 mvn -B -o -ntp -pl dataguard-server -am \
 '-Dtest=MySqlDatasourceTlsIntegrationTest' \
 '-Dsurefire.failIfNoSpecifiedTests=false' test
unset PW
python3 - "$ROOT/dataguard-server/target/surefire-reports" <<'PY'
import sys,pathlib,xml.etree.ElementTree as ET
path=pathlib.Path(sys.argv[1])
matches=list(path.glob("TEST-*MySqlDatasourceTlsIntegrationTest.xml"))
if len(matches)!=1:raise SystemExit("FAIL: expected one MySQL report")
root=ET.parse(matches[0]).getroot()
if (int(root.attrib.get('tests','0'))!=3 or
 int(root.attrib.get('failures','0'))!=0 or
 int(root.attrib.get('errors','0'))!=0 or
 int(root.attrib.get('skipped','0'))!=0):
 raise SystemExit("FAIL: MySQL SSL / physical ACL / DataGuard controller not all executed")
print('MYSQL_TLS_CONNECTION_PARSER_ACCEPTANCE_PASS (3 tests, 0 skipped)')
PY
