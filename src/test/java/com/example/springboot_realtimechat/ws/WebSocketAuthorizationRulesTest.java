package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.config.WebSocketAuthorizationConfig;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 규칙 자체를 검증한다. 소켓 없이 프레임을 만들어 판정만 본다.
 * 인가는 "되는 것"보다 "안 되는 것"을 고정하는 것이 중요하다.
 */
class WebSocketAuthorizationRulesTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long JOINED_ROOM = 1L;
    private static final Long OTHER_ROOM = 2L;

    private AuthorizationManager<Message<?>> manager;

    @BeforeEach
    void setUp() {
        RoomAccess roomAccess = mock(RoomAccess.class);
        when(roomAccess.isMember(eq(MEMBER_ID), eq(JOINED_ROOM))).thenReturn(true);
        when(roomAccess.isMember(eq(MEMBER_ID), eq(OTHER_ROOM))).thenReturn(false);
        manager = new WebSocketAuthorizationConfig().messageAuthorizationManager(roomAccess);
    }

    private static Message<?> frame(SimpMessageType type, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Supplier<Authentication> loggedIn() {
        CustomUserDetails details = new CustomUserDetails(MEMBER_ID, "u@test.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        return () -> auth;
    }

    private static Supplier<Authentication> anonymous() {
        return () -> null;
    }

    private boolean granted(Supplier<Authentication> auth, Message<?> message) {
        AuthorizationResult result = manager.authorize(auth, message);
        return result != null && result.isGranted();
    }

    @Test
    void 멤버는_방을_구독한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1/typing"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1/presence"))).isTrue();
    }

    @Test
    void 비멤버는_방을_구독하지_못한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/2"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/2/typing"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/2/presence"))).isFalse();
    }

    @Test
    void 멤버는_방에_전송한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/1/messages"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/1/typing"))).isTrue();
    }

    @Test
    void 비멤버는_방에_전송하지_못한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/2/messages"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/2/typing"))).isFalse();
    }

    @Test
    void 와일드카드_목적지는_멤버여도_거부한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/**"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/*"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/queue/**"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/?"))).isFalse();
    }

    @Test
    void URI_템플릿_목적지도_거부한다() {
        // AntPathMatcher가 '{'를 패턴으로 취급하므로 브로커에서 다시 패턴이 된다
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1;x={a}"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms;a={b}/1"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/user/queue/unread;x={a}"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/user/queue;a={b}/unread"))).isFalse();
    }

    @Test
    void 개인_큐는_인증되면_구독한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/user/queue/unread"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/user/queue/errors"))).isTrue();
    }

    @Test
    void 개인_큐_직접_구독은_거부한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/queue/unread"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/queue/errors"))).isFalse();
    }

    @Test
    void 규칙에_없는_목적지는_거부한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/notices"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/admin/broadcast"))).isFalse();
    }

    @Test
    void 미인증_CONNECT는_거부한다() {
        assertThat(granted(anonymous(), frame(SimpMessageType.CONNECT, null))).isFalse();
    }

    @Test
    void 인증된_CONNECT는_허용한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.CONNECT, null))).isTrue();
    }

    @Test
    void 연결_종료_계열은_미인증이어도_허용한다() {
        assertThat(granted(anonymous(), frame(SimpMessageType.DISCONNECT, null))).isTrue();
        assertThat(granted(anonymous(), frame(SimpMessageType.UNSUBSCRIBE, null))).isTrue();
        assertThat(granted(anonymous(), frame(SimpMessageType.HEARTBEAT, null))).isTrue();
    }

    @Test
    void 미인증은_방을_구독하지_못한다() {
        assertThat(granted(anonymous(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1"))).isFalse();
    }
}
