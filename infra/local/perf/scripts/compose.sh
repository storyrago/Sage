#!/usr/bin/env bash
# 기본 CDC 스택(docker-compose.yml)에 성능 측정 구성(docker-compose.perf.yml)을 덮어써 부른다. 인자는 docker compose에 그대로 넘긴다.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec docker compose -f docker-compose.yml -f docker-compose.perf.yml "$@"
