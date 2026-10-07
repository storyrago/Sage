#!/usr/bin/env bash
# 메시지 이벤트 스키마를 레지스트리에 등록한다. 앱은 auto-register를 끈 채 등록된 스키마만 쓴다.
# subject 호환성을 BACKWARD로 먼저 고정하므로, 호환되지 않는 변경은 여기서 거부된다(HTTP 409).
set -euo pipefail
cd "$(dirname "$0")/../../.."

SR_URL="${SR_URL:-http://localhost:8081}"
SUBJECT="chat.message.events-value"
SCHEMA_FILE="backend/src/main/resources/avro/message-event.avsc"

curl -sf -X PUT -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{"compatibility": "BACKWARD"}' \
  "$SR_URL/config/$SUBJECT"
echo

jq -Rs '{schema: .}' "$SCHEMA_FILE" | curl -sf -X POST \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data @- "$SR_URL/subjects/$SUBJECT/versions?normalize=true"
echo

curl -sf "$SR_URL/subjects/$SUBJECT/versions"
echo
