package com.example.springboot_realtimechat.global.redis;

import com.example.springboot_realtimechat.domain.presence.dto.TypingResponse;

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
public class TypingRedisSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            TypingResponse typing = objectMapper.readValue(message.getBody(), TypingResponse.class);
            messagingTemplate.convertAndSend(
                    "/sub/chatrooms/" + typing.getChatroomId() + "/typing", typing);
        } catch (Exception e) {
            log.error("Redis typing 역직렬화 실패", e);
        }
    }
}
