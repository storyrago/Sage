package com.example.springboot_realtimechat.domain.message.delivery;

import com.example.springboot_realtimechat.domain.chatroom.dto.UnreadEvent;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 새 메시지를 이 서버에 접속한 방 멤버(보낸 사람 제외)에게 안읽음으로 알린다.
 * 개인 큐(/user/..)는 그 사용자가 접속한 서버만 보낼 수 있어서, 모든 서버가 이벤트를 받고 각자 자기 접속자만 맡는다.
 * 접속자가 없는 서버는 조회하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
@RequiredArgsConstructor
public class LocalUnreadNotifier {

    private final MessageEventReader messageEventReader;
    private final SimpUserRegistry userRegistry;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(id = "unreadNotification", topics = MessageEventSchema.TOPIC,
            groupId = "sage-unread-#{@messageDeliveryNode.id()}")
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        notifyLocalMembers(messageEventReader.read(record.value()));
    }

    private void notifyLocalMembers(MessageEvent event) {
        // 수정·삭제는 새 메시지가 아니다. 보내면 배지가 부풀고 deleted=false만 세는 서버 집계와 어긋난다.
        if (event.getEventType() != MessageEventType.CREATED || event.getDeleted()) {
            return;
        }
        if (userRegistry.getUserCount() == 0) {
            return;
        }

        List<Long> recipients = chatRoomMemberRepository.findMemberIdsByChatRoomId(event.getChatroomId()).stream()
                .filter(memberId -> !memberId.equals(event.getAuthorId()))
                .filter(memberId -> userRegistry.getUser(String.valueOf(memberId)) != null)
                .toList();
        if (recipients.isEmpty()) {
            return;
        }

        // 조회는 보내기 전에 모두 끝낸다. 보내는 도중 예외로 재시도되면 이미 받은 사람이 같은 알림을 또 받는다.
        Long replyToAuthorId = event.getReplyToId() == null ? null : messageRepository.findAuthorIdById(event.getReplyToId());
        for (Long memberId : recipients) {
            UnreadEvent unread = new UnreadEvent(event.getChatroomId(), event.getMessageId(), memberId.equals(replyToAuthorId));
            try {
                messagingTemplate.convertAndSendToUser(String.valueOf(memberId), "/queue/unread", unread);
            } catch (Exception e) {
                log.warn("안읽음 전송 실패 (memberId={})", memberId, e);
            }
        }
    }
}
