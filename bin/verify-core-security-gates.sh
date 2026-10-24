#!/usr/bin/env bash
# Local, auditable, fail-closed release gate for the five core workstreams.
# DOES NOT deploy, touch an existing database, or enable production SQL execution.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
bash bin/preflight-release.sh
CACHE="${DG_M2_CACHE:-$HOME/.cache/mcp-dataguard/m2}"
mkdir -p "$CACHE"
if [[ "${DG_E2E_PREFETCH_MAVEN:-false}" == "true" ]]; then
  # Dependency download occurs separately. The actual database verification
  # below always runs in a no-network disposable PostgreSQL namespace.
  docker run --rm --network host \
    -v "$ROOT:/workspace" -v "$CACHE:/root/.m2" -w /workspace \
    maven:3.9.11-eclipse-temurin-21 \
    mvn -B -ntp -pl dataguard-server,dataguard-workflow -am \
      -Dtest=ApprovalOutboxIntegrationTest,ApprovalRecoveryIntegrationTest,ReadOnlyJdbcTlsIntegrationTest,CertifiedWriteJdbcTlsTest,ApprovedWritePipelineIntegrationTest,DeniedSqlAlertIntegrationTest,AuditIntegrityIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false test
fi
DG_RUN_FLOWABLE_OUTBOX_E2E=true \
DG_RUN_READ_TLS_E2E=true \
DG_RUN_WRITE_TLS_E2E=true \
DG_M2_CACHE="$CACHE" bash bin/verify-isolated-postgres.sh
# A green Maven exit is insufficient if optional integration tests silently skip.
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
files = {
  'dataguard-server': [
    'ReadOnlyJdbcTlsIntegrationTest',
    'CertifiedWriteJdbcTlsTest',
    'ApprovedWritePipelineIntegrationTest',
    'AuditIntegrityIntegrationTest',
    'DeniedSqlAlertIntegrationTest',
  ],
  'dataguard-workflow': [
    'ApprovalOutboxIntegrationTest',
    'ApprovalRecoveryIntegrationTest',
  ],
}
for module, suites in files.items():
  for suite in suites:
    reports=list((Path(module)/'target'/'surefire-reports').glob(f'TEST-*{suite}.xml'))
    if len(reports)!=1:
      raise SystemExit(f'FAIL: test report missing/ambiguous: {module}/{suite}')
    root=ET.parse(reports[0]).getroot()
    executed=int(root.attrib.get('tests','0'))
    skipped=int(root.attrib.get('skipped','0'))
    failed=int(root.attrib.get('failures','0'))+int(root.attrib.get('errors','0'))
    if executed<1 or skipped or failed:
      raise SystemExit(f'FAIL: {module}/{suite} tests={executed}, skipped={skipped}, failed={failed}')
    print(f'PASSED_REAL_PG: {module}/{suite} ({executed} tests)')
PY
echo "FULL_ISOLATED_CORE_SECURITY_ACCEPTANCE_PASS"
echo "Live read, write and workflow flags have NOT been enabled."
echo "Additional gates required before production: external MCP client, browser E2E,"
echo "controlled network failure/commit chaos, live backup restore and security review."
