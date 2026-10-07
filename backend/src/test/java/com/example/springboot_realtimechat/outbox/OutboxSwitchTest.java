package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventRecorder;
import com.example.springboot_realtimechat.domain.message.event.NoopMessageEventRecorder;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// 스위치를 켜지 않은 환경(기존 테스트·운영·CI)은 레지스트리 없이 그대로 동작해야 한다.
@SpringBootTest
class OutboxSwitchTest {

    @Autowired ApplicationContext context;

    @Test
    void 스위치가_꺼져_있으면_이벤트를_남기지_않고_레지스트리에도_연결하지_않는다() {
        assertThat(context.getBean(MessageEventRecorder.class)).isInstanceOf(NoopMessageEventRecorder.class);
        assertThat(context.getBeansOfType(KafkaAvroSerializer.class)).isEmpty();
        assertThat(context.getBeansOfType(SchemaRegistryClient.class)).isEmpty();
    }
}
