package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.global.outbox.OutboxConfig;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 레지스트리는 subject 호환성을 BACKWARD로 강제한다. 등록 단계에서 거부되기 전에 여기서 먼저 잡는다.
class MessageEventSchemaCompatibilityTest {

    private static final Schema V1 = load("/avro/history/message-event-v1.avsc");

    private static Schema load(String path) {
        try (InputStream in = MessageEventSchemaCompatibilityTest.class.getResourceAsStream(path)) {
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void 현재_스키마는_v1과_BACKWARD_호환이다() {
        assertThat(new AvroSchema(MessageEventSchema.SCHEMA).isBackwardCompatible(new AvroSchema(V1))).isEmpty();
    }

    @Test
    void 기본값_없는_필드를_더하면_호환성_검사에_걸린다() {
        // 위 검사가 실제로 무언가를 거르는지 확인한다.
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field field : MessageEventSchema.SCHEMA.getFields()) {
            fields.add(new Schema.Field(field, field.schema()));
        }
        fields.add(new Schema.Field("mandatory", Schema.create(Schema.Type.STRING)));
        Schema broken = Schema.createRecord(MessageEventSchema.SCHEMA.getName(), MessageEventSchema.SCHEMA.getDoc(),
                MessageEventSchema.SCHEMA.getNamespace(), false, fields);

        assertThat(new AvroSchema(broken).isBackwardCompatible(new AvroSchema(V1))).isNotEmpty();
    }

    @Test
    void v1으로_쓴_이벤트를_새_소비자가_읽으면_끊긴_이미지는_null이다() {
        String scope = "schema-compat-v1";
        try {
            MockSchemaRegistry.getClientForScope(scope).register(MessageEventSchema.SUBJECT, new AvroSchema(V1), true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GenericRecord written = new GenericRecordBuilder(V1)
                .set("eventId", UUID.randomUUID().toString())
                .set("eventType", new GenericData.EnumSymbol(V1.getField("eventType").schema(), "DELETED"))
                .set("occurredAt", 1_760_000_000_000L)
                .set("chatroomId", 1L)
                .set("messageId", 2L)
                .set("seq", 3L)
                .set("content", "")
                .set("createdAt", 1_760_000_000_000_000L)
                .set("deleted", true)
                .build();

        byte[] payload;
        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(
                MockSchemaRegistry.getClientForScope(scope), OutboxConfig.serializerConfig("mock://" + scope))) {
            payload = serializer.serialize(MessageEventSchema.TOPIC, written);
        }
        MessageEvent read = MessageEventTestSupport.deserialize(scope, payload);

        assertThat(read.getMessageId()).isEqualTo(2L);
        assertThat(read.getDereferencedImageUrl()).isNull();
    }
}
