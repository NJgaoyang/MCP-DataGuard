#!/bin/sh
# Inject Docker file secrets at runtime, not into docker inspect Config.Env.
# Passwords are still passed to JVM as environment variables because Spring's
# existing datasource configuration consumes env values. Host operators must
# treat /proc/<pid>/environ and Java memory as privileged secret surfaces.
set -eu
[ "$#" -eq 1 ] || exit 2
service="$1"
check_secret(){
  path="$1"
  [ -f "$path" ] && [ ! -L "$path" ] && [ -r "$path" ] || {
    echo "NO_GO: missing protected secret" >&2; exit 2;
  }
}
check_secret /run/secrets/platform_password
PG_PLATFORM_PASSWORD="$(cat /run/secrets/platform_password)"
[ -n "$PG_PLATFORM_PASSWORD" ] && [ "${#PG_PLATFORM_PASSWORD}" -ge 20 ] || {
  echo "NO_GO: platform password too short" >&2; exit 2;
}
export PG_PLATFORM_PASSWORD
case "$service" in
  gateway)
    exec java -jar /app/app.jar
    ;;
  workflow)
    check_secret /run/secrets/flowable_password
    PG_FLOWABLE_PASSWORD="$(cat /run/secrets/flowable_password)"
    [ -n "$PG_FLOWABLE_PASSWORD" ] && [ "${#PG_FLOWABLE_PASSWORD}" -ge 20 ] || {
      echo "NO_GO: workflow password too short" >&2; exit 2;
    }
    export PG_FLOWABLE_PASSWORD
    exec java -jar /app/app.jar
    ;;
  *)
    echo "NO_GO: unsupported service" >&2; exit 2
    ;;
esac
