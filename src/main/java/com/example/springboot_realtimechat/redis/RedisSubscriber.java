package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.dto.UnreadEvent;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    @Override
    public void onMessage(Message message, byte[] pattern){
        try{
            MessageResponse messageResponse = objectMapper.readValue(message.getBody(), MessageResponse.class);
            messagingTemplate.convertAndSend("/sub/chatrooms/" + messageResponse.getChatroomId(), messageResponse);

            // 안읽음 fan-out: 방 멤버(보낸 사람 제외)에게 개인 큐로 통지. 각 서버가 자기 로컬 세션에 라우팅(멀티서버 안전).
            List<Member> members = chatRoomMemberRepository.findMembersByChatRoomId(messageResponse.getChatroomId());
            UnreadEvent event = new UnreadEvent(messageResponse.getChatroomId(), messageResponse.getMessageId());
            for (Member member : members) {
                if (member.getId().equals(messageResponse.getMemberId())) continue; // 보낸 사람 제외
                messagingTemplate.convertAndSendToUser(member.getEmail(), "/queue/unread", event);
            }
        }catch(Exception e){
            log.error("Redis 메시지 역직렬화 실패", e);
        }

    }
}