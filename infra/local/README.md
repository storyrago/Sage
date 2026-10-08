# 로컬 CDC 파이프라인

메시지 변경을 outbox → MySQL binlog → Debezium(Kafka Connect) → Kafka 토픽 `chat.message.events`로 내보내는 로컬 전용 환경이다. 운영 compose(레포 루트 `docker-compose.yml`)와 별개다.

| 서비스 | 이미지 | 호스트 포트 |
|---|---|---|
| mysql | `mysql:8.0` (binlog ROW/FULL, GTID) | `${MYSQL_PORT:-3306}` |
| kafka-1~3 | `apache/kafka:4.3.1` (KRaft, 브로커+컨트롤러) | 29092, 39092, 49092 |
| schema-registry | `confluentinc/cp-schema-registry:8.3.2` | 8081 |
| connect | `quay.io/debezium/connect:3.7.0.Final` | 8083 |

준비물: Docker, `curl`, `jq`.

## 기동 순서

```bash
cd infra/local
docker compose up -d                 # 모든 서비스가 healthy가 될 때까지 기다린다
./scripts/create-topics.sh           # chat.message.events (RF 3, min ISR 2)
./scripts/register-schema.sh         # subject 호환성 BACKWARD + 스키마 등록
./scripts/register-connector.sh      # Debezium 커넥터 등록(이미 있으면 설정 갱신)
```

앱은 스위치를 켜고 띄운다(Redis는 기존처럼 별도로 떠 있어야 한다):

```bash
cd backend
APP_OUTBOX_ENABLED=true JWT_SECRET=local-dev-secret-0123456789abcdef0123 ./gradlew bootRun
```

스위치가 켜져 있는데 스키마가 등록돼 있지 않거나 레지스트리에 접근할 수 없으면 앱이 기동하지 않는다.

## 이벤트 확인

```bash
docker compose exec schema-registry kafka-avro-console-consumer \
  --bootstrap-server kafka-1:19092 --topic chat.message.events --from-beginning \
  --property schema.registry.url=http://schema-registry:8081 \
  --property print.key=true --property print.headers=true
```

## 정리

`docker compose stop`은 데이터를 보존하고, `docker compose down`은 Kafka 데이터를 지운다(MySQL은 볼륨에 남는다. 지우려면 `down -v`).

## Kafka 전달 모드로 앱 실행

스택을 띄우고 토픽·스키마·커넥터를 등록한 뒤(위 절차), 앱을 Kafka 경로로 띄운다.
서버를 여러 대 흉내 내려면 포트와 노드 id를 바꿔 한 번 더 띄운다(8081은 스키마 레지스트리가 쓴다).

```bash
cd backend
APP_OUTBOX_ENABLED=true APP_MESSAGE_DELIVERY=kafka APP_MESSAGE_NODE_ID=node-1 ./gradlew bootRun
# 두 번째 서버
APP_OUTBOX_ENABLED=true APP_MESSAGE_DELIVERY=kafka APP_MESSAGE_NODE_ID=node-2 SERVER_PORT=8090 ./gradlew bootRun
```

- 서버마다 consumer group 두 개가 생긴다: `sage-realtime-<노드 id>`(방 구독자 전달), `sage-unread-<노드 id>`(안읽음).
- 여러 대를 띄울 때는 `APP_MESSAGE_NODE_ID`를 서버마다 다르게, 재기동해도 같은 값으로 준다. 비우면 기동마다 새 UUID group이 생기고 옛 group은 브로커에 남는다(빈 group의 오프셋은 브로커 보존 기간 뒤 정리된다).
- 스키마 레지스트리에 닿지 못하면(접속 불가·타임아웃·5xx·401·403) 소비자는 이벤트를 DLT로 보내지 않고 복구될 때까지 다시 시도한다(최대 30초 간격). 그동안 해당 파티션은 멈추고 lag이 쌓인다.
- `APP_MESSAGE_DELIVERY=kafka`인데 `APP_OUTBOX_ENABLED`가 true가 아니면 앱이 뜨지 않는다.
- 상태 확인: `./scripts/status.sh` — 커넥터 상태, group별 lag, DLT 건수.
- DLT(`chat.message.events.DLT`)에는 원본 바이트와 헤더가 그대로 남는다. 헤더 `kafka_dlt-original-consumer-group`·`kafka_dlt-exception-message`로 어느 소비자가 왜 실패했는지 본다.
