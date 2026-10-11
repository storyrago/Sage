#!/usr/bin/env bash
# 두 모드 × 부하 형태 × 도착률을 반복마다 번갈아 돌린다(시간에 따른 노트북 상태 변화가 한 모드에만 몰리지 않게).
# 매 실행 전에 상태를 초기화하고 앱 2대를 새로 띄운다. 임계값을 넘겨도 결과는 저장하고 다음 실행으로 간다.
set -euo pipefail
cd "$(dirname "$0")/../.."   # infra/local
SCRIPTS=perf/scripts
RUNS="${RUNS:-3}"
SHAPES="${SHAPES:-hot spread}"
RATES="${RATES:-50 200}"
MODES="${MODES:-redis kafka}"
WARMUP_S="${WARMUP_S:-60}"
DURATION_S="${DURATION_S:-120}"
P99_LIMIT_MS="${P99_LIMIT_MS:-1000}"
# 실행 이름 끝에 붙는 묶음 표식. 같은 매트릭스를 다시 돌려도 회원 이메일·결과 파일이 겹치지 않는다.
STAMP="${STAMP:-$(date +%m%d%H%M)}"

for run in $(seq 1 "$RUNS"); do
  for shape in $SHAPES; do
    for rate in $RATES; do
      for mode in $MODES; do
        name="${mode}-${shape}-r${rate}-${run}-${STAMP}"
        echo "[perf] ===== ${name} ====="
        "$SCRIPTS/reset-state.sh"
        APP_MESSAGE_DELIVERY="$mode" "$SCRIPTS/compose.sh" up -d --no-deps --force-recreate --wait app-1 app-2
        "$SCRIPTS/seed-members.sh" "$name"
        "$SCRIPTS/compose.sh" run --rm \
          -e SHAPE="$shape" -e RATE="$rate" -e WARMUP_S="$WARMUP_S" -e DURATION_S="$DURATION_S" \
          -e RUN_NAME="$name" -e P99_LIMIT_MS="$P99_LIMIT_MS" \
          k6 run --tag testid="$name" --tag mode="$mode" -o experimental-prometheus-rw /scripts/chat.js \
          || echo "[perf] ${name}: 임계값 미달(결과는 results/${name}.json에 저장됨)"
      done
    done
  done
done
