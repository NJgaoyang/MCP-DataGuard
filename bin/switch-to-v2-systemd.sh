#!/usr/bin/env bash
# Controlled V2 cutover. Unlike systemctl stop '*dataguard*' it NEVER guesses.
# Does not modify metadata, credentials, or database data; no Docker in deployment.
set -euo pipefail
cd "$(dirname "$0")/.."
apply=false
old=""
for argument in "$@";do
  case "$argument" in
    --apply) apply=true ;;
    --old-unit=*) old="${argument#--old-unit=}" ;;
    *) echo "Unknown argument: $argument" >&2;exit 2 ;;
  esac
done
new=dataguard-standalone.service
jar=dataguard-standalone/target/dataguard-standalone-0.5.0-SNAPSHOT.jar
[[ -s "$jar" ]] || { echo "Missing verified JAR: run bin/build-standalone.sh" >&2;exit 2; }
if [[ -n "$old" ]];then
  [[ "$old" =~ ^dataguard-[a-zA-Z0-9_-]+\.service$ && "$old" != "$new" ]] || {
    echo "Refuse to stop unrelated or identical systemd service" >&2; exit 3;
  }
  systemctl show "$old" -p LoadState --value | grep -qx loaded || {
    echo "Old unit does not exist: $old" >&2;exit 3;
  }
fi
[[ -f /etc/dataguard/standalone.env ]] || {
  echo "Configure /etc/dataguard/standalone.env before cutover" >&2;exit 3;
}
echo "V2 CUTOVER PLAN: install $jar, stop ${old:-<none>}, start $new, verify loopback health"
if [[ "$apply" != true ]];then
  echo "PLAN ONLY: run again with --apply after reviewing old service and TLS configuration"
  exit 0
fi
[[ "$EUID" == 0 ]] || { echo "Use root for systemd deployment" >&2;exit 3; }
command -v systemctl >/dev/null
command -v curl >/dev/null
if [[ -z "$old" ]] && systemctl is-active --quiet "$new";then
  echo "Existing V2 is already running; use the normal verified upgrade procedure" >&2
  exit 3
fi
# Install files BEFORE service cutover. Refuses to overwrite existing secrets.
bash bin/install-standalone-systemd.sh
was_running=false
if [[ -n "$old" ]] && systemctl is-active --quiet "$old";then
  was_running=true
  systemctl stop "$old"
fi
restore_old(){
  systemctl stop "$new" >/dev/null 2>&1 || true
  if [[ "$was_running" == true ]];then
    systemctl start "$old" || true
  fi
}
trap restore_old ERR
systemctl enable --now "$new"
ready=false
for i in $(seq 1 45);do
  if curl --fail --silent --show-error --max-time 2 \
    http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"';then
    ready=true;break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo "V2 unhealthy; original unit restored by ERR trap" >&2;exit 1; }
trap - ERR
if [[ -n "$old" ]];then
  systemctl disable "$old"
fi
systemctl is-active "$new"
echo "V2_SYSTEMD_CUTOVER_HEALTH_PASS; old=${old:-none}"
