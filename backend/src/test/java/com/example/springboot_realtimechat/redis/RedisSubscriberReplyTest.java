package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.domain.chatroom.dto.UnreadEvent;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.global.redis.RedisSubscriber;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisSubscriberReplyTest {

    private SimpMessagingTemplate messagingTemplate;
    private ChatRoomMemberRepository chatRoomMemberRepository;
    private MessageRepository messageRepository;
    private RedisSubscriber subscriber;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        chatRoomMemberRepository = mock(ChatRoomMemberRepository.class);
        messageRepository = mock(MessageRepository.class);
        subscriber = new RedisSubscriber(
                messagingTemplate, new ObjectMapper(), chatRoomMemberRepository, messageRepository);
    }

    /** memberId만 알면 되므로 목으로 세운다. */
    private Member member(long id) {
        Member m = mock(Member.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    private Message redisMessage(String json) {
        Message m = mock(Message.class);
        when(m.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private String messageJson(long messageId, long senderId, Long replyToId) {
        return """
            {"messageId":%d,"content":"c","imageUrl":null,"memberId":%d,"nickname":"n",
             "profileImageUrl":null,"chatroomId":7,"createdAt":"2026-08-12T00:00:00",
             "replyToId":%s,"editedAt":null,"deleted":false}
            """.formatted(messageId, senderId, replyToId == null ? "null" : replyToId.toString());
    }

    @Test
    void 부모메시지_작성자에게만_replyToMe가_실린다() {
        // 방 멤버: 1(부모 작성자), 2(보낸 사람), 3(제3자)
        // member() 안에서 다시 when()을 호출하므로, thenReturn()의 인자로 바로 넘기면
        // 바깥 when()의 스터빙이 끝나기 전에 안쪽 when()이 끼어들어 Mockito가 예외를 던진다.
        List<Member> members = List.of(member(1L), member(2L), member(3L));
        when(chatRoomMemberRepository.findMembersByChatRoomId(7L)).thenReturn(members);
        when(messageRepository.findAuthorIdById(50L)).thenReturn(1L);

        subscriber.onMessage(redisMessage(messageJson(51L, 2L, 50L)), null);

        ArgumentCaptor<UnreadEvent> captor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), captor.capture());
        assertThat(captor.getValue().isReplyToMe()).isTrue();

        ArgumentCaptor<UnreadEvent> other = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("3"), eq("/queue/unread"), other.capture());
        assertThat(other.getValue().isReplyToMe()).isFalse();

        // 보낸 사람에겐 통지하지 않는다
        verify(messagingTemplate, never()).convertAndSendToUser(eq("2"), eq("/queue/unread"), any(Object.class));

        // 부모 작성자 조회는 멤버 수와 무관하게 메시지당 한 번만 실행된다
        verify(messageRepository, times(1)).findAuthorIdById(50L);
    }

    @Test
    void 답장이_아니면_부모조회를_하지_않는다() {
        List<Member> members = List.of(member(1L), member(2L));
        when(chatRoomMemberRepository.findMembersByChatRoomId(7L)).thenReturn(members);

        subscriber.onMessage(redisMessage(messageJson(51L, 2L, null)), null);

        verify(messageRepository, never()).findAuthorIdById(any());
        ArgumentCaptor<UnreadEvent> captor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), captor.capture());
        assertThat(captor.getValue().isReplyToMe()).isFalse();
    }

    @Test
    void 부모_작성자가_탈퇴했으면_아무도_replyToMe가_아니다() {
        List<Member> members = List.of(member(1L), member(2L));
        when(chatRoomMemberRepository.findMembersByChatRoomId(7L)).thenReturn(members);
        when(messageRepository.findAuthorIdById(50L)).thenReturn(null);

        subscriber.onMessage(redisMessage(messageJson(51L, 2L, 50L)), null);

        ArgumentCaptor<UnreadEvent> captor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), captor.capture());
        assertThat(captor.getValue().isReplyToMe()).isFalse();
    }
}
