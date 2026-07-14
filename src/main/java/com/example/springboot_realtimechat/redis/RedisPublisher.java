package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.MessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisPublisher {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic channelTopic;

    public void publish(MessageResponse message){
        redisTemplate.convertAndSend(channelTopic.getTopic(),message);
    }
}
