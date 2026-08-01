package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomSubscriptionRevokerTest {

    private SimpUserRegistry userRegistry;
    private MessageChannel clientInboundChannel;
    private ApplicationEventPublisher eventPublisher;
    private SimpMessagingTemplate messagingTemplate;
    private RoomSubscriptionRevoker revoker;

    @BeforeEach
    void setUp() {
        userRegistry = mock(SimpUserRegistry.class);
        clientInboundChannel = mock(MessageChannel.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        when(clientInboundChannel.send(any())).thenReturn(true);
        revoker = new RoomSubscriptionRevoker(
                userRegistry, clientInboundChannel, eventPublisher, messagingTemplate);
    }

    private SimpSubscription subscription(String id, String destination) {
        SimpSubscription subscription = mock(SimpSubscription.class);
        when(subscription.getId()).thenReturn(id);
        when(subscription.getDestination()).thenReturn(destination);
        return subscription;
    }

    private SimpSession session(String id, SimpSubscription... subscriptions) {
        SimpSession session = mock(SimpSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getSubscriptions()).thenReturn(new LinkedHashSet<>(List.of(subscriptions)));
        return session;
    }

    private void online(Long memberId, SimpSession... sessions) {
        SimpUser user = mock(SimpUser.class);
        when(user.getSessions()).thenReturn(new LinkedHashSet<>(List.of(sessions)));
        when(userRegistry.getUser(String.valueOf(memberId))).thenReturn(user);
    }

    /** 채널로 나간 프레임들의 subscriptionId 목록 */
    private List<String> revokedSubscriptionIds() {
        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.captor();
        verify(clientInboundChannel, org.mockito.Mockito.atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream()
                .map(m -> StompHeaderAccessor.wrap(m).getSubscriptionId())
                .toList();
    }

    @Test
    void 대상_방의_구독_3종만_회수한다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/3/typing"),
                subscription("sub-3", "/sub/chatrooms/3/presence"),
                subscription("sub-4", "/sub/chatrooms/9"),
                subscription("sub-5", "/user/queue/unread")));

        revoker.revokeRoom(7L, 3L);

        assertThat(revokedSubscriptionIds()).containsExactlyInAnyOrder("sub-1", "sub-2", "sub-3");
    }

    @Test
    void 방_3_회수가_방_30을_건드리지_않는다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/30"),
                subscription("sub-3", "/sub/chatrooms/30/typing")));

        revoker.revokeRoom(7L, 3L);

        assertThat(revokedSubscriptionIds()).containsExactly("sub-1");
    }

    @Test
    void 회수한_구독마다_구독해제_이벤트를_발행한다() {
        online(7L, session("s1", subscription("sub-1", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.captor();
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SessionUnsubscribeEvent.class);

        SessionUnsubscribeEvent event = (SessionUnsubscribeEvent) captor.getValue();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(accessor.getSessionId()).isEqualTo("s1");
        assertThat(accessor.getSubscriptionId()).isEqualTo("sub-1");
    }

    @Test
    void 회수한_방마다_통지를_한_건_보낸다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/3/typing")));

        revoker.revokeRoom(7L, 3L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.captor();
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq("/queue/errors"), payload.capture());

        WsErrorResponse sent = (WsErrorResponse) payload.getValue();
        assertThat(sent.code()).isEqualTo("ROOM_MEMBERSHIP_REVOKED");
        assertThat(sent.destination()).isEqualTo("/sub/chatrooms/3");
        assertThat(sent.message()).isNotBlank();
    }

    @Test
    void revokeAll은_모든_방을_회수하고_개인_큐는_남긴다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/9/typing"),
                subscription("sub-3", "/user/queue/unread"),
                subscription("sub-4", "/user/queue/errors")));

        revoker.revokeAll(7L);

        assertThat(revokedSubscriptionIds()).containsExactlyInAnyOrder("sub-1", "sub-2");
    }

    @Test
    void 여러_세션을_모두_처리한다() {
        online(7L,
                session("s1", subscription("sub-1", "/sub/chatrooms/3")),
                session("s2", subscription("sub-9", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        assertThat(revokedSubscriptionIds()).containsExactlyInAnyOrder("sub-1", "sub-9");
    }

    @Test
    void 세션이_없으면_아무것도_하지_않는다() {
        when(userRegistry.getUser("7")).thenReturn(null);

        revoker.revokeRoom(7L, 3L);

        verify(clientInboundChannel, never()).send(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 프레임이_폐기되면_이벤트를_발행하지_않는다() {
        when(clientInboundChannel.send(any())).thenReturn(false);
        online(7L, session("s1", subscription("sub-1", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 주입_프레임에_세션_속성을_싣지_않는다() {
        online(7L, session("s1", subscription("sub-1", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.captor();
        verify(clientInboundChannel).send(captor.capture());
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(captor.getValue());
        assertThat(accessor.getSessionAttributes()).isNull();
        assertThat(accessor.getUser()).isNull();
    }

    @Test
    void 알_수_없는_목적지는_회수하지_않는다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/abc"),
                subscription("sub-2", "/sub/notices")));

        revoker.revokeAll(7L);

        verify(clientInboundChannel, never()).send(any());
    }
}
