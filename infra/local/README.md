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
