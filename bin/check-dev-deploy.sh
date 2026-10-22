#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
for tool in git docker; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing $tool: install it using the host OS supported packages." >&2
    exit 1
  fi
done
docker compose version >/dev/null 2>&1 || { echo "Missing docker compose V2." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon unavailable or user lacks permission." >&2; exit 1; }
config=deploy/.env.dev
[[ -f "$config" ]] || { echo "Missing $config: copy deploy/.env.dev.example and edit." >&2; exit 1; }
if grep -Eiq 'REPLACE_WITH|example[.]invalid' "$config"; then
  echo "Placeholder password/OIDC value in $config; edit before deploying." >&2
  exit 1
fi
docker compose --env-file "$config" -f deploy/compose.yaml -f deploy/compose.dev.yaml config --quiet
echo "Docker and Compose configuration validated. This build has no production SQL execution."
