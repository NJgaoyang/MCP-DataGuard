#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null
command -v mvn >/dev/null
command -v node >/dev/null
command -v npm >/dev/null
# Build the Vue console into Spring Boot static assets: no separate web server.
(
  cd dataguard-web
  npm ci
  VITE_DATAGUARD_STANDALONE=true npm run build
)
test -s dataguard-web/dist/index.html
cp dataguard-web/dist/index.html dataguard-standalone/src/main/resources/static/index.html
cp -r dataguard-web/dist/assets dataguard-standalone/src/main/resources/static/
mvn -B -ntp -pl dataguard-standalone -am clean verify
printf 'MYSQL_VUE_SINGLE_JAR_OK: %s\n' "dataguard-standalone/target/dataguard-standalone-0.5.0-SNAPSHOT.jar"
