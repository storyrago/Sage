package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;
import com.example.springboot_realtimechat.outbox.MessageEventTestSupport;

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEventReaderTest {

    private static final String SCOPE = "reader-test";

    private MessageEventReader reader() {
        return new MessageEventReader(new KafkaAvroDeserializer(
                MockSchemaRegistry.getClientForScope(SCOPE), KafkaDeliveryConfig.deserializerConfig("mock://" + SCOPE)));
    }

    @Test
    void 레지스트리_와이어_포맷을_생성_클래스로_읽는다() {
        MessageEventTestSupport.register(SCOPE);
        MessageEvent event = MessageEventFixtures.event(MessageEventType.CREATED, 7L, 11L, 3L, 1L, null, false);

        MessageEvent read = reader().read(MessageEventTestSupport.serialize(SCOPE, event));

        assertThat(read).isEqualTo(event);
    }

    @Test
    void 해석할_수_없는_바이트는_직렬화_예외다() {
        // 이 예외는 재시도 없이 DLT로 간다(KafkaDeliveryConfig).
        assertThatThrownBy(() -> reader().read(new byte[]{1, 2, 3}))
                .isInstanceOf(SerializationException.class);
    }
}
