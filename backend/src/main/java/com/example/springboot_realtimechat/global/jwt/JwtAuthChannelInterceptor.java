package com.example.springboot_realtimechat.global.jwt;

import com.example.springboot_realtimechat.global.auth.CustomUserDetails;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

/** STOMP CONNECT의 Authorization 헤더를 검증해 세션 사용자로 세운다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthChannelInterceptor implements ChannelInterceptor {

    private static final String EXPIRES_AT = "tokenExpiresAt";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
            return message;
        }

        // 세션 종료 시 서버가 만드는 DISCONNECT도 이 채널을 지난다. 막으면 브로커가 구독을 정리하지 못한다.
        if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            return message;
        }

        // 연결 이후 프레임: 서명 검증 없이 기록된 만료 시각만 비교한다
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null
                && sessionAttributes.get(EXPIRES_AT) instanceof Long expiresAt
                && System.currentTimeMillis() >= expiresAt) {
            // 토큰 수명이 다한 정상적인 사건이라 WARN이 아니라 INFO로 남긴다.
            log.info("STOMP 세션 토큰 만료: command={}, principal={}, sessionId={}",
                    accessor.getCommand(), principalName(accessor), accessor.getSessionId());
            throw new AccessDeniedException("Access Denied");
        }

        return message;
    }

    // 정상 클라이언트라면 나지 않아야 하는 실패라 각 탈출 지점을 WARN으로 남긴다.
    private void authenticateConnect(StompHeaderAccessor accessor) {
        String authorizationHeader = accessor.getFirstNativeHeader("Authorization");
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            log.warn("STOMP 인증 실패: 사유=NO_TOKEN, sessionId={}", accessor.getSessionId());
            return;
        }
        String token = authorizationHeader.substring(7);

        if (!jwtTokenProvider.validateToken(token)) {
            log.warn("STOMP 인증 실패: 사유=INVALID_TOKEN, sessionId={}", accessor.getSessionId());
            return;
        }

        Long memberId = jwtTokenProvider.getMemberId(token);
        String email = jwtTokenProvider.getEmail(token);

        // REST와 같은 판정이다. 한쪽만 막으면 다른 쪽이 열린 채 남는다.
        boolean revoked = tokenDenylist.isRevoked(
                jwtTokenProvider.getJti(token),
                memberId,
                jwtTokenProvider.getIssuedAt(token));

        if (revoked) {
            log.warn("STOMP 인증 실패: 사유=REVOKED_TOKEN, memberId={}, sessionId={}", memberId, accessor.getSessionId());
            return;
        }

        CustomUserDetails userDetails = new CustomUserDetails(memberId, email);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());

        accessor.setUser(authentication);

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        Long expiresAt = jwtTokenProvider.getExpiresAt(token);
        if (sessionAttributes != null && expiresAt != null) {
            sessionAttributes.put(EXPIRES_AT, expiresAt);
        }

        log.info("STOMP 연결 인증: memberId={}, sessionId={}", memberId, accessor.getSessionId());
    }

    private String principalName(StompHeaderAccessor accessor) {
        return accessor.getUser() == null ? null : accessor.getUser().getName();
    }
}
