#!/usr/bin/env bash
# 메시지 이벤트 토픽을 만든다. 키(방 id)가 같은 이벤트는 같은 파티션으로 가므로 방 안 순서가 유지된다.
# 파티션 수는 나중에 늘리면 키 → 파티션 매핑이 바뀌어 방 안 순서가 깨질 수 있으니 처음에 정한다.
set -euo pipefail
cd "$(dirname "$0")/.."

PARTITIONS="${PARTITIONS:-6}"

TOPICS=(
  chat.message.events
  # 순서 보장 소비자(실시간·안읽음)가 처리하지 못한 이벤트(원본 바이트 그대로). 원본과 같은 파티션 번호로 보낸다.
  chat.message.events.DLT
  # 이미지 정리 소비자의 재시도 단계와 DLT. Spring은 원본과 같은 파티션 번호로 보내므로 파티션 수를 맞춘다.
  chat.message.events.image-cleanup-retry-0
  chat.message.events.image-cleanup-retry-1
  chat.message.events.image-cleanup-retry-2
  chat.message.events.image-cleanup-DLT
)

for topic in "${TOPICS[@]}"; do
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka-1:19092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor 3 \
    --config min.insync.replicas=2
done

for topic in "${TOPICS[@]}"; do
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka-1:19092 \
    --describe --topic "$topic"
done
