package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.global.outbox.OutboxEvent;
import com.example.springboot_realtimechat.global.outbox.OutboxWriter;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 이벤트를 레지스트리 와이어 포맷으로 직렬화해 outbox에 남긴다. 발행은 CDC(Debezium)가 binlog를 읽어 한다.
 * 직렬화기는 auto-register를 끈 채 등록된 스키마만 쓰므로, 호환성 검사를 통과하지 않은 스키마로는
 * 이벤트를 쓸 수 없고 그때는 메시지 변경까지 롤백된다.
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OutboxMessageEventRecorder implements MessageEventRecorder {

    private final KafkaAvroSerializer outboxAvroSerializer;
    private final OutboxWriter outboxWriter;

    @Override
    public void record(MessageEventType type, Message message) {
        UUID eventId = UUID.randomUUID();
        byte[] payload = outboxAvroSerializer.serialize(
                MessageEventSchema.TOPIC, MessageEventRecords.toEvent(eventId, type, Instant.now(), message));
        outboxWriter.append(new OutboxEvent(
                eventId.toString(),
                MessageEventSchema.AGGREGATE_TYPE,
                String.valueOf(message.getChatRoom().getId()),   // 키 = 방 id → 방 안 순서는 한 파티션 안에서 유지된다
                type.name(),
                payload));
    }
}
