#!/usr/bin/env bash
# 파이프라인 상태를 한 번에 본다: 커넥터·태스크 상태, consumer group별 lag, DLT에 쌓인 건수.
# lag은 브로커 기준이라 소비자가 죽어 있어도 보인다(앱 쪽 지표는 소비자가 살아 있을 때만 나온다).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== 커넥터 =="
curl -s http://localhost:8083/connectors/sage-outbox/status
echo

echo "== consumer group lag =="
docker compose exec -T kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:19092 --describe --all-groups

echo "== 순서 보장 소비자 DLT 건수(파티션별 끝 오프셋) =="
docker compose exec -T kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:19092 --topic chat.message.events.DLT

echo "== 이미지 정리 DLT 건수(파티션별 끝 오프셋) =="
docker compose exec -T kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka-1:19092 --topic chat.message.events.image-cleanup-DLT
