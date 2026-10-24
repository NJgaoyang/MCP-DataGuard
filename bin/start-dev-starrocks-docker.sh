#!/usr/bin/env bash
# Dedicated disposable StarRocks LAB only. Never touches unrelated containers.
set -euo pipefail
NAME=dg-v2-starrocks-dev
IMAGE=starrocks/allin1-ubuntu:3.5.21
command -v docker >/dev/null || { echo "Docker unavailable" >&2; exit 2; }
if docker ps -a --format '{{.Names}}' | grep -Fxq "$NAME"; then
  if [[ "$(docker inspect -f '{{.Config.Image}}' "$NAME")" != "$IMAGE" ]]; then
    echo "Refusing to overwrite existing $NAME with unexpected image" >&2; exit 3
  fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$NAME")" != true ]]; then
    docker start "$NAME" >/dev/null
  fi
else
  docker run -d --name "$NAME" --restart no \
    --label io.mcp-dataguard.scope=dev-starrocks-v2 \
    -p 127.0.0.1:9030:9030 -p 127.0.0.1:8030:8030 \
    "$IMAGE" >/dev/null
fi
echo "StarRocks LAB container: $NAME (loopback 127.0.0.1:9030)"
for i in $(seq 1 90);do
  if docker exec "$NAME" mysql --connect-timeout=3 -h 127.0.0.1 -P 9030 -uroot \
    -N -e 'SELECT 1' 2>/dev/null | grep -Fxq 1;then
    echo STARROCKS_DOCKER_LAB_READY
    exit 0
  fi
  sleep 3
done
echo "StarRocks failed to become ready; inspect: docker logs $NAME" >&2
exit 1
