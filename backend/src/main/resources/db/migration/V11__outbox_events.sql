-- 메시지 이벤트 outbox. 메시지 변경과 같은 트랜잭션에서 INSERT하고 곧바로 DELETE한다.
-- 행은 남지 않지만 binlog에는 INSERT가 남고, Debezium이 그것을 읽어 Kafka로 보낸다(DELETE는 Outbox Event Router가 버린다).
-- 열 이름은 Debezium Outbox Event Router 기본값(id, aggregatetype, aggregateid, type, payload)을 따른다.
CREATE TABLE outbox_events (
    id            VARCHAR(36)      NOT NULL,   -- 이벤트 id(UUID). Kafka 헤더 id로 나가 소비자 중복 제거 키가 된다
    aggregatetype VARCHAR(255)     NOT NULL,   -- 토픽 결정(chat.<값>.events)
    aggregateid   VARCHAR(255)     NOT NULL,   -- Kafka 키(방 id). 같은 방은 같은 파티션
    type          VARCHAR(255)     NOT NULL,   -- 이벤트 종류. Kafka 헤더 eventType
    payload       VARBINARY(16384) NOT NULL,   -- 스키마 레지스트리 와이어 포맷 Avro 바이트
    PRIMARY KEY (id)
) ENGINE=InnoDB;
