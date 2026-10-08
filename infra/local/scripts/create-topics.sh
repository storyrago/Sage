#!/usr/bin/env bash
# 메시지 이벤트 토픽을 만든다. 키(방 id)가 같은 이벤트는 같은 파티션으로 가므로 방 안 순서가 유지된다.
# 파티션 수는 나중에 늘리면 키 → 파티션 매핑이 바뀌어 방 안 순서가 깨질 수 있으니 처음에 정한다.
set -euo pipefail
cd "$(dirname "$0")/.."

PARTITIONS="${PARTITIONS:-6}"

docker compose exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:19092 \
  --create --if-not-exists \
  --topic chat.message.events \
  --partitions "$PARTITIONS" \
  --replication-factor 3 \
  --config min.insync.replicas=2

docker compose exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka-1:19092 \
  --describe --topic chat.message.events
