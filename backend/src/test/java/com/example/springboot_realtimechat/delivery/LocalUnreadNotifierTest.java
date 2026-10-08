package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.chatroom.dto.UnreadEvent;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.message.delivery.LocalUnreadNotifier;
import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LocalUnreadNotifierTest {

    private static final long ROOM = 7L;
    private static final byte[] PAYLOAD = {0};

    private MessageEventReader reader;
    private SimpUserRegistry userRegistry;
    private ChatRoomMemberRepository chatRoomMemberRepository;
    private MessageRepository messageRepository;
    private SimpMessagingTemplate messagingTemplate;
    private LocalUnreadNotifier notifier;

    @BeforeEach
    void setUp() {
        reader = mock(MessageEventReader.class);
        userRegistry = mock(SimpUserRegistry.class);
        chatRoomMemberRepository = mock(ChatRoomMemberRepository.class);
        messageRepository = mock(MessageRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        notifier = new LocalUnreadNotifier(reader, userRegistry, chatRoomMemberRepository, messageRepository, messagingTemplate);
    }

    /** 이 서버에 접속한 사용자를 정한다. STOMP 사용자 이름은 회원 id 문자열이다. */
    private void connectedHere(long... memberIds) {
        when(userRegistry.getUserCount()).thenReturn(memberIds.length);
        for (long id : memberIds) {
            when(userRegistry.getUser(String.valueOf(id))).thenReturn(mock(SimpUser.class));
        }
    }

    private void receive(MessageEvent event) {
        when(reader.read(PAYLOAD)).thenReturn(event);
        notifier.onEvent(new ConsumerRecord<>("chat.message.events", 0, 0L, String.valueOf(ROOM), PAYLOAD));
    }

    private MessageEvent created(long messageId, long authorId, Long replyToId) {
        return MessageEventFixtures.event(MessageEventType.CREATED, ROOM, messageId, 1L, authorId, replyToId, false);
    }

    private Set<String> notifiedUsers() {
        ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate, atLeast(0)).convertAndSendToUser(users.capture(), eq("/queue/unread"), any(Object.class));
        return Set.copyOf(users.getAllValues());
    }

    @Test
    void 이_서버에_접속한_방_멤버에게만_보낸다() {
        // 방 멤버 1·2·3·4, 보낸 사람 2, 이 서버 접속자 1·3·9(9는 방 멤버 아님). 4는 다른 서버 담당.
        when(chatRoomMemberRepository.findMemberIdsByChatRoomId(ROOM)).thenReturn(List.of(1L, 2L, 3L, 4L));
        connectedHere(1L, 2L, 3L, 9L);

        receive(created(51L, 2L, null));

        assertThat(notifiedUsers()).containsExactlyInAnyOrder("1", "3");
    }

    @Test
    void 부모_메시지_작성자에게만_replyToMe가_실린다() {
        when(chatRoomMemberRepository.findMemberIdsByChatRoomId(ROOM)).thenReturn(List.of(1L, 2L, 3L));
        connectedHere(1L, 3L);
        when(messageRepository.findAuthorIdById(50L)).thenReturn(1L);

        receive(created(51L, 2L, 50L));

        ArgumentCaptor<UnreadEvent> toParentAuthor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), toParentAuthor.capture());
        assertThat(toParentAuthor.getValue().isReplyToMe()).isTrue();
        assertThat(toParentAuthor.getValue().getChatroomId()).isEqualTo(ROOM);
        assertThat(toParentAuthor.getValue().getMessageId()).isEqualTo(51L);

        ArgumentCaptor<UnreadEvent> toOther = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("3"), eq("/queue/unread"), toOther.capture());
        assertThat(toOther.getValue().isReplyToMe()).isFalse();

        // 부모 작성자 조회는 받는 사람 수와 무관하게 이벤트당 한 번
        verify(messageRepository, times(1)).findAuthorIdById(50L);
    }

    @Test
    void 답장이_아니면_부모를_조회하지_않는다() {
        when(chatRoomMemberRepository.findMemberIdsByChatRoomId(ROOM)).thenReturn(List.of(1L, 2L));
        connectedHere(1L);

        receive(created(51L, 2L, null));

        verify(messageRepository, never()).findAuthorIdById(anyLong());
        assertThat(notifiedUsers()).containsExactly("1");
    }

    @Test
    void 이_서버에_접속자가_없으면_조회하지_않는다() {
        when(userRegistry.getUserCount()).thenReturn(0);

        receive(created(51L, 2L, 50L));

        verifyNoInteractions(chatRoomMemberRepository, messageRepository, messagingTemplate);
    }

    @Test
    void 이_서버에_받을_사람이_없으면_부모도_조회하지_않는다() {
        when(chatRoomMemberRepository.findMemberIdsByChatRoomId(ROOM)).thenReturn(List.of(1L, 2L));
        connectedHere(2L, 9L);   // 접속자는 보낸 사람과 방 밖 사용자뿐

        receive(created(51L, 2L, 50L));

        verify(messageRepository, never()).findAuthorIdById(anyLong());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void 수정과_삭제는_새_메시지가_아니라_보내지_않는다() {
        connectedHere(1L);

        receive(MessageEventFixtures.event(MessageEventType.UPDATED, ROOM, 51L, 1L, 2L, null, false));
        receive(MessageEventFixtures.event(MessageEventType.DELETED, ROOM, 51L, 1L, 2L, null, true));

        verifyNoInteractions(chatRoomMemberRepository, messagingTemplate);
    }

    @Test
    void 한_명에게_보내기가_실패해도_나머지는_받는다() {
        // 예외를 올리면 이벤트가 재시도돼 이미 받은 사람이 같은 알림을 또 받는다.
        when(chatRoomMemberRepository.findMemberIdsByChatRoomId(ROOM)).thenReturn(List.of(1L, 2L, 3L));
        connectedHere(1L, 3L);
        doThrow(new MessagingException("세션 종료")).when(messagingTemplate)
                .convertAndSendToUser(eq("1"), anyString(), any(Object.class));

        receive(created(51L, 2L, null));

        verify(messagingTemplate).convertAndSendToUser(eq("3"), eq("/queue/unread"), any(Object.class));
    }

    @Test
    void 작성자가_탈퇴한_메시지도_접속한_멤버_모두에게_보낸다() {
        when(chatRoomMemberRepository.findMemberIdsByChatRoomId(ROOM)).thenReturn(List.of(1L, 3L));
        connectedHere(1L, 3L);

        receive(MessageEventFixtures.event(MessageEventType.CREATED, ROOM, 51L, 1L, null, null, false));

        assertThat(notifiedUsers()).containsExactlyInAnyOrder("1", "3");
    }
}
