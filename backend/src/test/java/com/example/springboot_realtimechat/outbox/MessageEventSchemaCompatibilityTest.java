package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.global.outbox.OutboxConfig;

import io.confluent.kafka.schemaregistry.CompatibilityChecker;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 레지스트리는 subject 호환성을 BACKWARD_TRANSITIVE로 강제한다(infra/local/scripts/register-schema.sh).
// 등록 단계에서 거부되기 전에 같은 판정기로 여기서 먼저 잡는다.
// 스키마를 바꿀 때는 바꾸기 전 .avsc를 avro/history/message-event-vN.avsc로 남긴다.
class MessageEventSchemaCompatibilityTest {

    private static final CompatibilityChecker CHECKER =
            CompatibilityChecker.checker(CompatibilityLevel.BACKWARD_TRANSITIVE);

    private static final Schema V1 = load("/avro/history/message-event-v1.avsc");

    private static Schema load(String path) {
        try (InputStream in = MessageEventSchemaCompatibilityTest.class.getResourceAsStream(path)) {
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 기준본 전부를 버전 순(오래된 것 먼저)으로 읽는다. */
    private static List<AvroSchema> history() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:avro/history/message-event-v*.avsc");
            List<AvroSchema> schemas = new ArrayList<>();
            for (Resource file : Arrays.stream(files)
                    .sorted(Comparator.comparingInt(MessageEventSchemaCompatibilityTest::version))
                    .toList()) {
                try (InputStream in = file.getInputStream()) {
                    schemas.add(new AvroSchema(new Schema.Parser().parse(in)));
                }
            }
            return schemas;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int version(Resource file) {
        String name = file.getFilename();
        return Integer.parseInt(name.substring("message-event-v".length(), name.length() - ".avsc".length()));
    }

    @Test
    void 현재_스키마는_모든_기준본과_BACKWARD_TRANSITIVE_호환이다() {
        List<AvroSchema> history = history();
        // 경로가 틀려 비교 대상이 비면 검사가 조용히 통과하므로 막는다.
        assertThat(history).isNotEmpty();

        assertThat(CHECKER.isCompatible(new AvroSchema(MessageEventSchema.SCHEMA), history)).isEmpty();
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

        assertThat(CHECKER.isCompatible(new AvroSchema(broken), history())).isNotEmpty();
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
