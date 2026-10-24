#!/usr/bin/env bash
# Manual, non-Docker installation only; does not create secrets or start services.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${EUID}" -ne 0 ]];then echo "Run as root on approved application host" >&2;exit 2;fi
command -v java >/dev/null || { echo "Install Java 21 first" >&2;exit 2; }
command -v systemctl >/dev/null
jar="dataguard-standalone/target/dataguard-standalone-0.5.0-SNAPSHOT.jar"
[[ -s "$jar" ]] || { echo "Build standalone JAR first" >&2;exit 2; }
if ! id dataguard >/dev/null 2>&1;then
 useradd --system --home-dir /var/lib/dataguard --shell /usr/sbin/nologin dataguard
fi
install -d -m 0750 -o dataguard -g dataguard /opt/dataguard /var/lib/dataguard
install -d -m 0700 -o root -g root /etc/dataguard
install -m 0440 -o dataguard -g dataguard "$jar" /opt/dataguard/dataguard-standalone.jar
install -m 0644 -o root -g root deploy/systemd/dataguard-standalone.service /etc/systemd/system/dataguard-standalone.service
if [[ ! -f /etc/dataguard/standalone.env ]];then
  install -m 0600 -o root -g root deploy/standalone.env.example /etc/dataguard/standalone.env
  echo "Installed protected template: configure MySQL credentials and database TLS before enabling service."
else
  echo "Existing /etc/dataguard/standalone.env preserved."
fi
systemctl daemon-reload
echo "NON_DOCKER_INSTALL_PREPARED; service remains stopped. DBA must provision MySQL and operator must approve service start."
