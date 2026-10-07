package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;

import java.util.Map;

// 레지스트리 없이 직렬화를 검증하기 위한 모의 레지스트리(mock://<scope>) 도우미.
final class MessageEventTestSupport {

    private MessageEventTestSupport() {
    }

    /** 배포 단계의 스키마 등록(register-schema.sh)을 모의 레지스트리에 재현한다. */
    static void register(String scope) {
        try {
            MockSchemaRegistry.getClientForScope(scope)
                    .register(MessageEventSchema.SUBJECT, new AvroSchema(MessageEventSchema.SCHEMA), true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static GenericRecord deserialize(String scope, byte[] payload) {
        try (KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(
                MockSchemaRegistry.getClientForScope(scope),
                Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://" + scope))) {
            return (GenericRecord) deserializer.deserialize(MessageEventSchema.TOPIC, payload);
        }
    }
}
