package com.example.springboot_realtimechat.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.message.node-id=test-node")
class KafkaDeliveryWiringTest {

    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired CommonErrorHandler commonErrorHandler;
    @Autowired SimpleBrokerMessageHandler simpleBrokerMessageHandler;

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

    @Test
    void 세션마다_보낸_순서대로_내보낸다() {
        // 소비자는 방 순서대로 보낸다. 송신 채널의 스레드 풀이 그 순서를 섞지 않게 한다.
        assertThat(simpleBrokerMessageHandler.isPreservePublishOrder()).isTrue();
    }
}
