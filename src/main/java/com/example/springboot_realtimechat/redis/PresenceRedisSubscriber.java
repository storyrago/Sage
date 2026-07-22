package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.PresenceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceRedisSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            PresenceResponse presence = objectMapper.readValue(message.getBody(), PresenceResponse.class);
            messagingTemplate.convertAndSend("/sub/chatrooms/" + presence.getRoomId() + "/presence", presence);
        } catch (Exception e) {
            log.error("Redis presence 역직렬화 실패", e);
        }
    }
}
