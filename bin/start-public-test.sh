#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ -f deploy/.env.dev && -f deploy/.env.public ]] || {
  echo "Prepare deploy/.env.dev and deploy/.env.public first." >&2; exit 1;
}
bash bin/check-dev-deploy.sh
set -a
source deploy/.env.dev
source deploy/.env.public
set +a
[[ -n "${DG_PUBLIC_HOST:-}" && "$DG_PUBLIC_HOST" != *example.invalid ]] || {
  echo "Set the new server's valid DNS hostname in deploy/.env.public." >&2; exit 1;
}
[[ -n "${ACME_EMAIL:-}" && "$ACME_EMAIL" != *example.invalid ]] || {
  echo "Set ACME email." >&2; exit 1;
}
[[ "${VITE_OIDC_REDIRECT_URI:-}" == "https://${DG_PUBLIC_HOST}/auth/callback" ]] || {
  echo "VITE_OIDC_REDIRECT_URI must use https://${DG_PUBLIC_HOST}/auth/callback" >&2; exit 1;
}
docker compose --env-file deploy/.env.dev --env-file deploy/.env.public \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml -f deploy/compose.public.yaml \
  config --quiet
docker compose --env-file deploy/.env.dev --env-file deploy/.env.public \
  -f deploy/compose.yaml -f deploy/compose.dev.yaml -f deploy/compose.public.yaml \
  up -d --build postgres gateway web public
echo "Public test URL: https://${DG_PUBLIC_HOST}"
