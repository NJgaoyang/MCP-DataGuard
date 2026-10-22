#!/usr/bin/env bash
set -euo pipefail
: "${PG_PLATFORM_USER:?}"
: "${PG_PLATFORM_PASSWORD:?}"
: "${PG_FLOWABLE_USER:?}"
: "${PG_FLOWABLE_PASSWORD:?}"
# Role names interpolated below are constrained by a strict local bootstrap policy.
for name in "$PG_PLATFORM_USER" "$PG_FLOWABLE_USER"; do
  [[ "$name" =~ ^[a-z_][a-z0-9_]{1,40}$ ]] || { echo "Invalid role name" >&2; exit 1; }
done
export PGPASSWORD="$POSTGRES_PASSWORD"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v puser="$PG_PLATFORM_USER" -v ppass="$PG_PLATFORM_PASSWORD" \
  -v fuser="$PG_FLOWABLE_USER" -v fpass="$PG_FLOWABLE_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'puser', :'ppass') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'fuser', :'fpass') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('CREATE SCHEMA platform AUTHORIZATION %I', :'puser') \gexec
SELECT format('CREATE SCHEMA flowable AUTHORIZATION %I', :'fuser') \gexec
SQL
