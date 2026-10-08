package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.events.MessageEvent;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 토픽에서 받은 바이트(레지스트리 와이어 포맷)를 MessageEvent로 읽는다. 소비자들이 공유한다. */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
@RequiredArgsConstructor
public class MessageEventReader {

    private final KafkaAvroDeserializer messageEventAvroDeserializer;

    /** 해석할 수 없으면 SerializationException — 재시도하지 않고 DLT로 간다. */
    public MessageEvent read(byte[] payload) {
        Object value = messageEventAvroDeserializer.deserialize(MessageEventSchema.TOPIC, payload);
        if (value instanceof MessageEvent event) {
            return event;
        }
        throw new SerializationException("MessageEvent가 아닌 값: " + (value == null ? "null" : value.getClass().getName()));
    }
}
