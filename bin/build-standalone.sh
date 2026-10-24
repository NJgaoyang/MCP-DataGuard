#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null
command -v mvn >/dev/null
mvn -B -ntp -pl dataguard-standalone -am clean verify
printf 'MONOLITH_BUILD_OK: %s\n' "dataguard-standalone/target/dataguard-standalone-0.5.0-SNAPSHOT.jar"