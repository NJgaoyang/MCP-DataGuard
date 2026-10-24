#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
bash bin/check-dev-deploy.sh
docker compose --env-file deploy/.env.dev \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml -f deploy/compose.hardened.yaml \
  up -d --build postgres gateway web
docker compose --env-file deploy/.env.dev \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml ps
echo "Console loopback URL: http://127.0.0.1:18080 (unless configured otherwise)."
echo "Use SSH port forwarding from your workstation. SQL execution is disabled."
