#!/usr/bin/env bash
# Debezium 커넥터를 만들거나(없으면) 설정을 갱신한다(있으면). PUT /connectors/{name}/config는 둘 다 처리한다.
set -euo pipefail
cd "$(dirname "$0")/.."

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONFIG_FILE="connect/outbox-connector.json"
NAME="$(jq -r .name "$CONFIG_FILE")"

jq .config "$CONFIG_FILE" | curl -sf -X PUT -H "Content-Type: application/json" \
  --data @- "$CONNECT_URL/connectors/$NAME/config" > /dev/null

for _ in $(seq 30); do
  status="$(curl -sf "$CONNECT_URL/connectors/$NAME/status" || true)"
  if [ "$(echo "$status" | jq -r '.connector.state + "/" + (.tasks[0].state // "")')" = "RUNNING/RUNNING" ]; then
    echo "$status" | jq .
    exit 0
  fi
  sleep 2
done
echo "커넥터가 RUNNING이 되지 않았다:" >&2
echo "$status" | jq . >&2
exit 1
