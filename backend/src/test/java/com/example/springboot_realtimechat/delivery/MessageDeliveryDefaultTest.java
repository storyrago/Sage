package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.image.event.InProcessMessageImageRelease;
import com.example.springboot_realtimechat.domain.image.event.MessageImageRelease;
import com.example.springboot_realtimechat.domain.message.delivery.MessageBroadcaster;
import com.example.springboot_realtimechat.domain.message.delivery.RedisMessageBroadcaster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;

// 스위치를 주지 않으면 지금 동작(Redis 방송, STOMP 기본 설정) 그대로다.
@SpringBootTest
class MessageDeliveryDefaultTest {

    @Autowired MessageBroadcaster messageBroadcaster;
    @Autowired SimpleBrokerMessageHandler simpleBrokerMessageHandler;
    @Autowired MessageImageRelease messageImageRelease;

    @Test
    void 기본_방송기는_Redis_경로다() {
        assertThat(messageBroadcaster).isInstanceOf(RedisMessageBroadcaster.class);
    }

    @Test
    void 기본_STOMP_설정은_바뀌지_않는다() {
        assertThat(simpleBrokerMessageHandler.isPreservePublishOrder()).isFalse();
    }

    @Test
    void 기본_경로의_메시지_이미지_정리는_커밋_후_리스너가_한다() {
        assertThat(messageImageRelease).isInstanceOf(InProcessMessageImageRelease.class);
    }
}
