package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomAuthorizationChannelInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomAuthorizationInterceptorTest {

    private AuthorizationManager<Message<?>> manager;
    private SimpMessagingTemplate messagingTemplate;
    private RoomAuthorizationChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        manager = mock(AuthorizationManager.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(messagingTemplate);
        interceptor = new RoomAuthorizationChannelInterceptor(manager, provider);
    }

    private Message<?> frame(StompCommand command, String destination, Authentication user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Authentication loggedIn() {
        CustomUserDetails details = new CustomUserDetails(7L, "u@test.com");
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private void decide(boolean granted) {
        when(manager.authorize(any(), any())).thenReturn(new AuthorizationDecision(granted));
    }

    @Test
    void 허용되면_프레임을_그대로_통과시킨다() {
        decide(true);
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/1", loggedIn());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 인가_실패는_프레임을_버리고_개인_큐로_사유를_보낸다() {
        decide(false);
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/2", loggedIn());

        assertThat(interceptor.preSend(message, channel)).isNull();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq("/queue/errors"), payload.capture());

        WsErrorResponse sent = (WsErrorResponse) payload.getValue();
        assertThat(sent.code()).isEqualTo("NOT_JOINED_ROOM");
        assertThat(sent.destination()).isEqualTo("/sub/chatrooms/2");
        assertThat(sent.message()).isNotBlank();
    }

    @Test
    void 미인증_CONNECT_거부는_예외로_세션을_닫는다() {
        decide(false);
        Message<?> message = frame(StompCommand.CONNECT, null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 사용자가_없는_거부는_알릴_대상이_없으므로_예외로_닫는다() {
        decide(false);
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/1", null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 인가_실패해도_세션은_유지된다_예외를_던지지_않는다() {
        decide(false);
        Message<?> message = frame(StompCommand.SEND, "/pub/chatrooms/2/messages", loggedIn());

        assertThat(interceptor.preSend(message, channel)).isNull();
    }
}
