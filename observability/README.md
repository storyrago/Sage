# 로컬 관측 스택

로컬 개발용 Prometheus + Grafana. 운영 배포와는 무관하다.

## 띄우는 순서

1. 앱을 `./gradlew bootRun`으로 띄운다.
2. `docker compose -f docker-compose.observability.yml up -d`

## 접속

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (초기 계정 admin/admin, 첫 로그인 시 비밀번호 변경 요구됨)

Grafana에서 JVM 대시보드를 보려면 Dashboards > Import에서 대시보드 ID **4701**(JVM Micrometer)을 입력한다.
데이터소스(Prometheus)는 이미 등록돼 있어 따로 선택할 필요 없다.

## 확인 방법

- `curl -s localhost:8080/actuator/prometheus | head` 로 앱이 값을 내보내는지 확인한다.
- Prometheus의 Status > Targets에서 `sage-app`이 UP인지 확인한다.

## 내리는 법

`docker compose -f docker-compose.observability.yml down` (볼륨까지 지우려면 `-v` 추가)
