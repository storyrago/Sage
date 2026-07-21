package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.dto.PresenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisPublisher {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic channelTopic;    // "chatroom" (빈 이름으로 해석)
    private final ChannelTopic presenceTopic;   // "presence" (빈 이름으로 해석)

    public void publish(MessageResponse message){
        redisTemplate.convertAndSend(channelTopic.getTopic(),message);
    }

    public void publishPresence(PresenceResponse presence){
        redisTemplate.convertAndSend(presenceTopic.getTopic(), presence);
    }
}
