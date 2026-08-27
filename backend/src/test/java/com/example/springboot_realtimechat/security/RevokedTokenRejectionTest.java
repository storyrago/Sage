package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.global.jwt.JwtAuthChannelInterceptor;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.global.jwt.TokenDenylist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서명과 만료를 통과해도 무효화된 토큰은 인증되면 안 된다. REST와 WebSocket 양쪽에서 본다.
@SpringBootTest
@AutoConfigureMockMvc
class RevokedTokenRejectionTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TokenDenylist denylist;
    @Autowired JwtAuthChannelInterceptor interceptor;
    @Autowired StringRedisTemplate redis;

    private static final Long MEMBER_ID = 987655L;

    private final MessageChannel channel = mock(MessageChannel.class);

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
    }

    private StompHeaderAccessor connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> sent = interceptor.preSend(message, channel);
        return MessageHeaderAccessor.getAccessor(sent, StompHeaderAccessor.class);
    }

    @Test
    void 무효화되지_않은_토큰은_REST를_통과한다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 무효화된_토큰의_REST_요청은_401이다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        denylist.revokeToken(jwtTokenProvider.getJti(token), jwtTokenProvider.getExpiresAt(token));

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 같은_회원의_다른_토큰은_계속_유효하다() throws Exception {
        String revoked = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        String other = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        denylist.revokeToken(jwtTokenProvider.getJti(revoked), jwtTokenProvider.getExpiresAt(revoked));

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());
    }

    @Test
    void 회원_단위_무효화_이전_토큰의_REST_요청은_401이다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        Thread.sleep(1_100L);   // iat는 초 단위다. 무효화 시각이 발급 시각보다 확실히 뒤여야 한다
        denylist.revokeMember(MEMBER_ID);

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 무효화되지_않은_토큰의_CONNECT는_사용자를_세운다() {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");

        assertThat(connect(token).getUser()).isNotNull();
    }

    @Test
    void 무효화된_토큰의_CONNECT는_사용자를_세우지_않는다() {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        denylist.revokeToken(jwtTokenProvider.getJti(token), jwtTokenProvider.getExpiresAt(token));

        assertThat(connect(token).getUser()).isNull();
    }

    @Test
    void 회원_단위_무효화_이전_토큰의_CONNECT는_사용자를_세우지_않는다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        Thread.sleep(1_100L);
        denylist.revokeMember(MEMBER_ID);

        assertThat(connect(token).getUser()).isNull();
    }
}
