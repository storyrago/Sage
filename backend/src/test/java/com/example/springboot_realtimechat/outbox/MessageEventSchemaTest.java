package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEventSchemaTest {

    @Test
    void 토픽과_subject는_커넥터_라우팅_규칙과_같은_이름이다() {
        // 커넥터의 route.topic.replacement=chat.${routedByValue}.events, TopicNameStrategy = <토픽>-value
        assertThat(MessageEventSchema.TOPIC).isEqualTo("chat.message.events");
        assertThat(MessageEventSchema.SUBJECT).isEqualTo("chat.message.events-value");
        assertThat(MessageEventSchema.SCHEMA.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(MessageEventSchema.SCHEMA.getField("seq")).isNotNull();
    }

    @Test
    void 등록되지_않은_스키마는_거부한다() {
        assertThatThrownBy(() -> MessageEventSchema.requireRegistered(
                MockSchemaRegistry.getClientForScope("schema-test-empty")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MessageEventSchema.SUBJECT);
    }

    @Test
    void 등록된_스키마는_id를_돌려준다() {
        MessageEventTestSupport.register("schema-test-registered");

        int id = MessageEventSchema.requireRegistered(MockSchemaRegistry.getClientForScope("schema-test-registered"));

        assertThat(id).isPositive();
    }
}
