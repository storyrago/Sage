package com.example.springboot_realtimechat.global.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Debezium Outbox Event Router가 읽는 outbox 행. 열 이름은 라우터 기본값을 따른다.
 * 행은 쓰자마자 지운다(OutboxWriter) — 발행은 binlog의 INSERT로 일어난다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final int MAX_PAYLOAD_BYTES = 16384;

    // 이벤트 id(UUID). Kafka 헤더 id가 되어 소비자가 중복을 걸러내는 키로 쓴다.
    @Id
    @Column(length = 36)
    private String id;

    // 토픽을 정한다(chat.<값>.events).
    @Column(name = "aggregatetype", nullable = false)
    private String aggregateType;

    // Kafka 키. 같은 키는 같은 파티션으로 가서 순서가 유지된다.
    @Column(name = "aggregateid", nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String type;

    // 스키마 레지스트리 와이어 포맷(매직 바이트 + 스키마 id + Avro 본문).
    @Column(nullable = false, length = MAX_PAYLOAD_BYTES)
    private byte[] payload;

    public OutboxEvent(String id, String aggregateType, String aggregateId, String type, byte[] payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
    }
}
