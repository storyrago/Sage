package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.outbox.MessageEventTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.message.delivery=kafka",
        "app.message.node-id=test-node",
        "app.outbox.enabled=true",
        "app.outbox.schema-registry-url=mock://" + KafkaDeliveryWiringTest.SCOPE,
        "spring.kafka.listener.auto-startup=false"
})
class KafkaDeliveryWiringTest {

    static final String SCOPE = "kafka-wiring-test";

    static {
        MessageEventTestSupport.register(SCOPE);
    }

    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired CommonErrorHandler commonErrorHandler;

    @Test
    void 실시간_전달은_서버마다_고유한_group으로_읽는다() {
        // 내장 STOMP 브로커는 자기 서버의 세션에만 보낸다. 모든 서버가 모든 이벤트를 받아야 한다.
        assertThat(listenerRegistry.getListenerContainer("realtimeDelivery").getGroupId())
                .isEqualTo("sage-realtime-test-node");
    }

    @Test
    void 처리_실패는_재시도_후_DLT로_보낸다() {
        assertThat(commonErrorHandler).isInstanceOf(DefaultErrorHandler.class);
    }

    @Test
    void 두_리스너_모두_같은_에러_핸들러_빈을_쓴다() {
        assertThat(((AbstractMessageListenerContainer<?, ?>) listenerRegistry.getListenerContainer("realtimeDelivery"))
                .getCommonErrorHandler()).isSameAs(commonErrorHandler);
        assertThat(((AbstractMessageListenerContainer<?, ?>) listenerRegistry.getListenerContainer("unreadNotification"))
                .getCommonErrorHandler()).isSameAs(commonErrorHandler);
    }

    @Test
    void 안읽음도_서버마다_고유한_group으로_읽는다() {
        // 안읽음 통지도 개인 큐(/user/..)라 그 사용자가 접속한 서버만 보낼 수 있다.
        assertThat(listenerRegistry.getListenerContainer("unreadNotification").getGroupId())
                .isEqualTo("sage-unread-test-node");
    }
}
