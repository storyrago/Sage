package com.example.springboot_realtimechat.global.redis;

import com.example.springboot_realtimechat.domain.chatroom.dto.UnreadEvent;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;

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
    private final MessageRepository messageRepository;
    @Override
    public void onMessage(Message message, byte[] pattern){
        MessageResponse messageResponse;
        try{
            messageResponse = objectMapper.readValue(message.getBody(), MessageResponse.class);
            messagingTemplate.convertAndSend("/sub/chatrooms/" + messageResponse.getChatroomId(), messageResponse);
        }catch(Exception e){
            log.error("Redis 메시지 역직렬화 실패", e);
            return;
        }

        // 수정/삭제 재전파는 새 메시지가 아니므로 안읽음 이벤트를 보내지 않는다
        // (안 그러면 배지가 부풀고, deleted=false만 세는 서버 집계와 어긋남)
        if (messageResponse.getEditedAt() == null && !messageResponse.isDeleted()) {
            try {
                // 답장이면 부모 작성자를 한 번만 조회한다. 방 멤버 순회 안에서 조회하면 멤버 수만큼 쿼리가 늘어난다.
                Long replyToAuthorId = messageResponse.getReplyToId() == null
                        ? null
                        : messageRepository.findAuthorIdById(messageResponse.getReplyToId());

                // 안읽음 fan-out: 방 멤버(보낸 사람 제외)에게 개인 큐로 통지. 각 서버가 자기 로컬 세션에 라우팅(멀티서버 안전).
                List<Member> members = chatRoomMemberRepository.findMembersByChatRoomId(messageResponse.getChatroomId());
                for (Member member : members) {
                    if (member.getId().equals(messageResponse.getMemberId())) continue; // 보낸 사람 제외
                    boolean replyToMe = replyToAuthorId != null && replyToAuthorId.equals(member.getId());
                    UnreadEvent event = new UnreadEvent(
                            messageResponse.getChatroomId(), messageResponse.getMessageId(), replyToMe);
                    try {
                        messagingTemplate.convertAndSendToUser(String.valueOf(member.getId()), "/queue/unread", event);
                    } catch (Exception e) {
                        log.warn("안읽음 전송 실패 (memberId={})", member.getId(), e);
                    }
                }
            } catch (Exception e) {
                log.error("안읽음 fan-out 실패 (chatroomId={})", messageResponse.getChatroomId(), e);
            }
        }
    }
}