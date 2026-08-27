package com.example.springboot_realtimechat.global.redis;

import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.presence.dto.PresenceResponse;
import com.example.springboot_realtimechat.domain.presence.dto.TypingResponse;

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
    private final ChannelTopic typingTopic;     // "typing" (빈 이름으로 해석)

    public void publish(MessageResponse message){
        redisTemplate.convertAndSend(channelTopic.getTopic(),message);
    }

    public void publishPresence(PresenceResponse presence){
        redisTemplate.convertAndSend(presenceTopic.getTopic(), presence);
    }

    public void publishTyping(TypingResponse typing){
        redisTemplate.convertAndSend(typingTopic.getTopic(), typing);
    }
}
