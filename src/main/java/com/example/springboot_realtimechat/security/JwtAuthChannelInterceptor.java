package com.example.springboot_realtimechat.security;

import lombok.RequiredArgsConstructor;
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
@Component
@RequiredArgsConstructor
public class JwtAuthChannelInterceptor implements ChannelInterceptor {

    private static final String EXPIRES_AT = "tokenExpiresAt";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authorizationHeader = accessor.getFirstNativeHeader("Authorization");

            if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                String token = authorizationHeader.substring(7);

                if (jwtTokenProvider.validateToken(token)) {
                    Long memberId = jwtTokenProvider.getMemberId(token);
                    String email = jwtTokenProvider.getEmail(token);

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
                }
            }
            return message;
        }

        // 연결 이후 프레임: 서명 검증 없이 기록된 만료 시각만 비교한다
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null
                && sessionAttributes.get(EXPIRES_AT) instanceof Long expiresAt
                && System.currentTimeMillis() >= expiresAt) {
            throw new AccessDeniedException("Access Denied");
        }

        return message;
    }
}
