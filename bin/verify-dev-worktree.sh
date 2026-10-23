#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if command -v mvn >/dev/null && command -v java >/dev/null; then
  mvn -B -ntp verify
else
  command -v docker >/dev/null || { echo "Java/Maven or Docker required" >&2; exit 1; }
  CACHE="${DG_M2_CACHE:-$HOME/.cache/mcp-dataguard/m2}"
  mkdir -p "$CACHE"
  docker run --rm -v "$ROOT:/workspace" -v "$CACHE:/root/.m2" -w /workspace \
    maven:3.9.11-eclipse-temurin-21 mvn -B -ntp verify
fi
if command -v npm >/dev/null && command -v node >/dev/null; then
  cd "$ROOT/dataguard-web"
  if test -f package-lock.json; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
  npm run build
else
  docker run --rm -v "$ROOT/dataguard-web:/workspace" -w /workspace node:22-alpine \
    sh -c 'npm install --no-audit --no-fund && npm run build'
fi
echo "Backend and frontend build verification passed."
