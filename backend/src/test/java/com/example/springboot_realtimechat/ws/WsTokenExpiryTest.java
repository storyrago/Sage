package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.global.jwt.JwtAuthChannelInterceptor;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.global.jwt.TokenDenylist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WsTokenExpiryTest {

    private static final String SECRET = "test-secret-key-for-jwt-authentication-1234567890";

    private JwtAuthChannelInterceptor interceptor;
    private JwtTokenProvider provider;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3600000L);
        // 이 테스트는 만료 처리만 본다. 거부목록 판정은 RevokedTokenRejectionTest가 본다.
        TokenDenylist denylist = mock(TokenDenylist.class);
        interceptor = new JwtAuthChannelInterceptor(provider, denylist);
    }

    private Message<?> frame(StompCommand command, Map<String, Object> sessionAttributes, String bearer) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionAttributes(sessionAttributes);
        if (bearer != null) {
            accessor.setNativeHeader("Authorization", "Bearer " + bearer);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void CONNECT가_만료_시각을_세션에_기록한다() {
        Map<String, Object> attrs = new HashMap<>();
        String token = provider.createAccessToken(7L, "u@test.com");

        interceptor.preSend(frame(StompCommand.CONNECT, attrs, token), channel);

        Object expiresAt = attrs.get("tokenExpiresAt");
        assertThat(expiresAt).isInstanceOf(Long.class);
        assertThat((Long) expiresAt).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void 만료_전_프레임은_통과한다() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tokenExpiresAt", System.currentTimeMillis() + 60_000L);
        Message<?> message = frame(StompCommand.SEND, attrs, null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 만료된_세션의_프레임은_거부한다() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tokenExpiresAt", System.currentTimeMillis() - 1L);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, attrs, null), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 만료된_세션의_DISCONNECT는_통과시킨다() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tokenExpiresAt", System.currentTimeMillis() - 1L);
        Message<?> message = frame(StompCommand.DISCONNECT, attrs, null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 만료_기록이_없으면_통과시킨다() {
        Map<String, Object> attrs = new HashMap<>();
        Message<?> message = frame(StompCommand.SEND, attrs, null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 만료_시각을_토큰에서_읽는다() {
        String token = provider.createAccessToken(7L, "u@test.com");
        Long expiresAt = provider.getExpiresAt(token);

        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void 잘못된_토큰의_만료_시각은_null() {
        assertThat(provider.getExpiresAt("not-a-token")).isNull();
    }
}
