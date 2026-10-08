package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.global.outbox.OutboxConfig;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

import java.util.Map;

// 레지스트리 없이 직렬화를 검증하기 위한 모의 레지스트리(mock://<scope>) 도우미. 다른 패키지 테스트도 쓴다.
public final class MessageEventTestSupport {

    private MessageEventTestSupport() {
    }

    /** 배포 단계의 스키마 등록(register-schema.sh)을 모의 레지스트리에 재현한다. */
    public static void register(String scope) {
        try {
            MockSchemaRegistry.getClientForScope(scope)
                    .register(MessageEventSchema.SUBJECT, new AvroSchema(MessageEventSchema.SCHEMA), true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 운영과 같은 직렬화기 설정(OutboxConfig.serializerConfig)으로 직렬화한다. */
    public static byte[] serialize(String scope, MessageEvent event) {
        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(
                MockSchemaRegistry.getClientForScope(scope), OutboxConfig.serializerConfig("mock://" + scope))) {
            return serializer.serialize(MessageEventSchema.TOPIC, event);
        }
    }

    public static MessageEvent deserialize(String scope, byte[] payload) {
        try (KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(
                MockSchemaRegistry.getClientForScope(scope), Map.of(
                        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://" + scope,
                        KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true))) {
            return (MessageEvent) deserializer.deserialize(MessageEventSchema.TOPIC, payload);
        }
    }
}
