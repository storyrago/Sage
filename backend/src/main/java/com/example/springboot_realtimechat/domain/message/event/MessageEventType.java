package com.example.springboot_realtimechat.domain.message.event;

/** Avro 스키마의 MessageEventType enum, outbox type 열, Kafka 헤더 eventType이 모두 이 이름을 쓴다. */
public enum MessageEventType {
    CREATED,
    UPDATED,
    DELETED
}
