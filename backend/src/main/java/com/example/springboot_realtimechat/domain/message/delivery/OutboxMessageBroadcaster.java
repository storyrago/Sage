package com.example.springboot_realtimechat.domain.message.delivery;

import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka 경로에서는 컨트롤러가 따로 방송하지 않는다. 메시지와 같은 트랜잭션에서 커밋된 outbox 이벤트가
 * Debezium → Kafka → 각 서버의 소비자로 전파된다(커밋되면 반드시, 롤백되면 전혀).
 */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
public class OutboxMessageBroadcaster implements MessageBroadcaster {

    @Override
    public void broadcast(MessageResponse response) {
    }
}
