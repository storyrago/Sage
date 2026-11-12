#!/usr/bin/env bash
# 메시지 이벤트 스키마를 레지스트리에 등록한다. 앱은 auto-register를 끈 채 등록된 스키마만 쓴다.
# subject 호환성을 BACKWARD_TRANSITIVE(과거 버전 전부와 비교)로 먼저 고정하므로, 호환되지 않는 변경은 여기서 거부된다(HTTP 409).
# --with-history: 기준본(backend/src/test/resources/avro/history)을 버전 순으로 먼저 등록한다.
#   빈 레지스트리(CI)에서도 과거 버전과의 호환성을 실제 레지스트리가 판정하게 한다.
set -euo pipefail
cd "$(dirname "$0")/../../.."

SR_URL="${SR_URL:-http://localhost:8081}"
SUBJECT="chat.message.events-value"
SCHEMA_FILE="backend/src/main/resources/avro/message-event.avsc"
HISTORY_DIR="backend/src/test/resources/avro/history"

register() {
  echo "register $1"
  jq -Rs '{schema: .}' "$1" | curl -sS --fail-with-body -X POST \
    -H "Content-Type: application/vnd.schemaregistry.v1+json" \
    --data @- "$SR_URL/subjects/$SUBJECT/versions?normalize=true"
  echo
}

curl -sS --fail-with-body -X PUT -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{"compatibility": "BACKWARD_TRANSITIVE"}' \
  "$SR_URL/config/$SUBJECT"
echo

if [[ "${1:-}" == "--with-history" ]]; then
  for file in $(ls "$HISTORY_DIR"/message-event-v*.avsc | sort -V); do
    register "$file"
  done
fi

register "$SCHEMA_FILE"

curl -sS --fail-with-body "$SR_URL/subjects/$SUBJECT/versions"
echo
