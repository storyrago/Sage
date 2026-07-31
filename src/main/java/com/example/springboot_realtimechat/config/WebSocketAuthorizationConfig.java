package com.example.springboot_realtimechat.config;

import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.util.matcher.MessageMatcher;

/**
 * STOMP 프레임 인가 규칙. 위에서부터 처음 일치하는 규칙이 판정하고, 마지막은 기본 거부다.
 * 규칙을 쓰지 않은 목적지는 막힌 채로 시작한다.
 */
@Configuration
public class WebSocketAuthorizationConfig {

    @Bean
    public AuthorizationManager<Message<?>> messageAuthorizationManager(RoomAccess roomAccess) {
        AuthorizationManager<MessageAuthorizationContext<?>> roomMember = roomMember(roomAccess);

        MessageMatcherDelegatingAuthorizationManager.Builder messages =
                MessageMatcherDelegatingAuthorizationManager.builder();

        messages
                // 브로커가 구독 목적지를 패턴으로 취급하므로, 와일드카드는 규칙 평가 이전에 막는다
                .matchers(wildcardDestination()).denyAll()

                .simpTypeMatchers(SimpMessageType.CONNECT).authenticated()
                .simpTypeMatchers(SimpMessageType.DISCONNECT,
                                  SimpMessageType.UNSUBSCRIBE,
                                  SimpMessageType.HEARTBEAT).permitAll()

                .simpSubscribeDestMatchers("/user/queue/unread", "/user/queue/errors").authenticated()

                .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}",
                                           "/sub/chatrooms/{chatroomId}/typing",
                                           "/sub/chatrooms/{chatroomId}/presence").access(roomMember)

                .simpMessageDestMatchers("/pub/chatrooms/{chatroomId}/messages",
                                         "/pub/chatrooms/{chatroomId}/typing").access(roomMember)

                .anyMessage().denyAll();

        return messages.build();
    }

    private AuthorizationManager<MessageAuthorizationContext<?>> roomMember(RoomAccess roomAccess) {
        return (authentication, context) -> {
            Long roomId = parseLongOrNull(context.getVariables().get("chatroomId"));
            Long memberId = memberIdOf(authentication.get());
            return new AuthorizationDecision(roomAccess.isMember(memberId, roomId));
        };
    }

    /**
     * 목적지에 패턴 문자가 들어오면 거부한다. 정상 클라이언트는 리터럴 목적지만 보낸다.
     * AntPathMatcher는 '*'·'?'뿐 아니라 '{'로 시작하는 URI 템플릿 변수도 패턴으로 취급한다.
     */
    private MessageMatcher<Object> wildcardDestination() {
        return message -> {
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            if (destination == null) {
                return false;
            }
            return destination.indexOf('*') >= 0
                    || destination.indexOf('?') >= 0
                    || destination.indexOf('{') >= 0;
        };
    }

    private static Long parseLongOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long memberIdOf(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails details)) {
            return null;
        }
        return details.getMemberId();
    }
}
