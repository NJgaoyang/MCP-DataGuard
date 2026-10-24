#!/usr/bin/env bash
# Disposable fixture tests only. No production secrets, volumes, services or DNS.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)";chmod 700 "$tmp"
trap 'rm -rf "$tmp"' EXIT
printf 'ci_only_very_long_platform_secret\n' > "$tmp/p"
printf 'ci_only_very_long_flowable_secret\n' > "$tmp/f"
chmod 640 "$tmp/p" "$tmp/f"
if [ "$(id -u)" = 0 ]; then chown 0:10001 "$tmp/p" "$tmp/f"; else sudo chown 0:10001 "$tmp/p" "$tmp/f"; fi
printf '%s\n%s\n%s\n' '-----BEGIN CERTIFICATE-----' "$(printf a%.0s {1..64})" '-----END CERTIFICATE-----' > "$tmp/ca"
chmod 644 "$tmp/ca"
export DG_GATEWAY_IMAGE="example.invalid/dataguard/gateway@sha256:$(printf 'a%.0s' {1..64})"
export DG_WORKFLOW_IMAGE="example.invalid/dataguard/workflow@sha256:$(printf 'b%.0s' {1..64})"
export DG_WEB_IMAGE="example.invalid/dataguard/web@sha256:$(printf 'c%.0s' {1..64})"
export DG_CADDY_IMAGE="example.invalid/caddy@sha256:$(printf 'd%.0s' {1..64})"
export DG_PUBLIC_HOST="dataguard.ci.example.org"
export DG_PLATFORM_JDBC_URL="jdbc:postgresql://pg-platform.ci.example.org:5432/dataguard?currentSchema=platform&sslmode=verify-full&sslrootcert=/run/secrets/postgres_ca"
export DG_FLOWABLE_JDBC_URL="jdbc:postgresql://pg-flowable.ci.example.org:5432/dataguard?currentSchema=flowable&sslmode=verify-full&sslrootcert=/run/secrets/postgres_ca"
export DG_PLATFORM_USER="dg_platform"
export DG_FLOWABLE_USER="dg_flowable"
export DG_PROD_PLATFORM_PASSWORD_FILE="$tmp/p"
export DG_PROD_FLOWABLE_PASSWORD_FILE="$tmp/f"
export DG_PROD_POSTGRES_CA_FILE="$tmp/ca"
bash bin/verify-production-compose.sh | grep -q PRODUCTION_COMPOSE_NO_DEPLOYMENT_PASS
must_fail(){
 local name="$1";shift
 if "$@" >/dev/null 2>&1;then echo "FAIL: $name accepted" >&2;exit 1;fi
}
must_fail floating-tag env DG_GATEWAY_IMAGE=example/gateway:latest bash bin/verify-production-compose.sh
must_fail plaintext-transport env DG_PLATFORM_JDBC_URL='jdbc:postgresql://pg.test:5432/db?sslmode=require' bash bin/verify-production-compose.sh
must_fail loopback-db env DG_PLATFORM_JDBC_URL='jdbc:postgresql://127.0.0.1:5432/db?sslmode=verify-full' bash bin/verify-production-compose.sh
must_fail unreadable-secret env DG_PROD_PLATFORM_PASSWORD_FILE="$tmp/missing" bash bin/verify-production-compose.sh
if [ "$(id -u)" = 0 ]; then chmod 644 "$tmp/p"; else sudo chmod 644 "$tmp/p"; fi
must_fail world-readable-secret bash bin/verify-production-compose.sh
echo "PRODUCTION_COMPOSE_NEGATIVE_POLICY_PASS"
