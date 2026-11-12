#!/usr/bin/env bash
# 실행 사이 조건을 맞춘다: 앱을 멈추고, CDC가 이전 실행의 이벤트를 다 내보낼 때까지 기다린 뒤,
# 소비자 group의 오프셋을 토픽 끝으로 옮긴다(앱이 멈춘 사이 쌓인 이벤트를 다음 실행이 몰아 처리하지 않게).
# DB는 지우지 않는다 — 실행마다 새 회원·방을 써서 서로 섞이지 않게 한다.
set -euo pipefail
COMPOSE="$(dirname "$0")/compose.sh"

"$COMPOSE" stop app-1 app-2

end_offsets() {
  "$COMPOSE" exec -T kafka-1 /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server kafka-1:19092 --topic chat.message.events | sort
}
previous=""
current="$(end_offsets)"
while [ "$current" != "$previous" ]; do
  previous="$current"
  sleep 5
  current="$(end_offsets)"
done
echo "[perf] CDC 유휴 확인(토픽 끝 오프셋 5초간 변화 없음)"

for group in sage-realtime-app-1 sage-realtime-app-2 sage-unread-app-1 sage-unread-app-2 sage-image-cleanup; do
  # 처음 실행이라 group이 없으면 실패한다 — 옮길 오프셋이 없다는 뜻이라 무시한다.
  "$COMPOSE" exec -T kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka-1:19092 --group "$group" --topic chat.message.events \
    --reset-offsets --to-latest --execute > /dev/null 2>&1 || true
done
echo "[perf] 소비자 오프셋을 토픽 끝으로 옮김"
