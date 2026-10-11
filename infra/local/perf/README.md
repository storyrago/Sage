# 성능 측정 스택

redis·kafka 두 메시지 전파 경로에 같은 부하를 걸어 종단 지연·처리량·오류율을 비교한다. 기본 CDC 스택(`../docker-compose.yml`)에 `../docker-compose.perf.yml`을 덮어써서 쓴다 — 항상 `perf/scripts/compose.sh`로 부른다.

| 구성 | 주소 |
|---|---|
| 앱 서버 2대 (`app-1`, `app-2`, CPU 2·메모리 1g 고정) | 호스트 18080, 18090 (지표·health는 내부 관리 포트 8091) |
| Prometheus | http://localhost:9090 |
| Grafana (로그인 없음, 폴더 Sage) | http://localhost:3000 |
| kafka-exporter (브로커 기준 lag) | 내부 9308 |
| Debezium JMX (jmx_exporter) | 내부 9404 |

## 기동

```bash
cd backend && ./gradlew bootJar && cd ../infra/local
perf/scripts/compose.sh build app-1 connect
perf/scripts/compose.sh up -d mysql kafka-1 kafka-2 kafka-3 schema-registry connect redis
./scripts/create-topics.sh && ./scripts/register-schema.sh && ./scripts/register-connector.sh
perf/scripts/compose.sh up -d --wait app-1 app-2 kafka-exporter prometheus grafana
```

## 측정

```bash
perf/scripts/run-matrix.sh            # 기본: 3회 × (hot, spread) × (50, 200 msg/s) × (redis, kafka), 실행당 약 4분
```

환경 변수로 줄일 수 있다: `RUNS`, `SHAPES`, `RATES`, `MODES`, `WARMUP_S`, `DURATION_S`, `P99_LIMIT_MS`.

- 실행마다: 앱 정지 → CDC 유휴 대기 → 소비자 오프셋을 토픽 끝으로 → 앱 2대 새로 기동 → 새 회원 30명·새 방 → 워밍업 60초(결과 제외) → 측정 120초.
- 결과: 콘솔의 `[perf]` 줄, `perf/results/<모드>-<형태>-r<도착률>-<회차>.json`(k6 요약), Grafana 대시보드 3개.
- 종단 지연 = 보낸 쪽이 본문에 넣은 시각 → 다른 받는 사람이 STOMP로 받은 시각. 송수신이 한 k6 프로세스라 시계가 같다.

## 한계

모든 구성이 노트북 한 대에서 돈다. 부하 발생기(k6)와 대상이 CPU를 나눠 쓰므로 숫자는 같은 조건에서 두 모드의 **상대 비교**이고, 절대 처리 용량이 아니다. DB는 실행 사이에 지우지 않는다(실행마다 새 회원·방으로 분리).
