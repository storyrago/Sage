package com.example.springboot_realtimechat.domain.message.delivery;

import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 저장 뒤 Redis Pub/Sub으로 모든 서버에 방송한다(기본 경로). */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisMessageBroadcaster implements MessageBroadcaster {

    private final RedisPublisher redisPublisher;

    @Override
    public void broadcast(MessageResponse response) {
        redisPublisher.publish(response);
    }
}
