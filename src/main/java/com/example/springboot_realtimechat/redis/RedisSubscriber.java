package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.dto.MessageResponse;
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
public class RedisSubscriber implements MessageListener {
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    @Override
    public void onMessage(Message message, byte[] pattern){
        try{
            MessageResponse messageResponse = objectMapper.readValue(message.getBody(), MessageResponse.class);
            messagingTemplate.convertAndSend("/sub/chatrooms/" + messageResponse.getChatroomId(), messageResponse);
        }catch(Exception e){
            log.error("Redis 메시지 역직렬화 실패", e);
        }

    }
}