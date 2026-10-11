#!/usr/bin/env bash
# 실행마다 측정용 회원 30명(보내는 사람 10 + 받는 사람 20)을 새로 넣는다. 가입 API가 없어 DB에 직접 넣는다.
# 비밀번호는 데모 계정과 같은 demo1234(V4__seed_demo.sql의 BCrypt 해시). 이메일: perf-<실행 이름>-<번호>@perf.local
set -euo pipefail
RUN_NAME="$1"
COUNT="${COUNT:-30}"
HASH='$2y$10$./I/HRInW7FBcewDKVwYCumiJMCjP2bILGD4jT6jTwf6ihDGdxmiK'

values=""
for i in $(seq 1 "$COUNT"); do
  values+="('perf-${RUN_NAME}-${i}@perf.local','${HASH}','perf${i}','LOCAL',NOW(6),NOW(6)),"
done

# 비밀번호는 환경 변수로 넘긴다(명령줄 -p는 경고를 stderr로 낸다). 실패(예: 같은 실행 이름의 중복 이메일)는 그대로 보이게 둔다.
"$(dirname "$0")/compose.sh" exec -T -e MYSQL_PWD=1111 mysql mysql -uroot spring_realtimechat_service \
  -e "INSERT INTO members (email, password, nickname, provider, created_at, onboarded_at) VALUES ${values%,};"
echo "[perf] 회원 ${COUNT}명 삽입: perf-${RUN_NAME}-*"
