package com.example.springboot_realtimechat.ws;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인가 규칙이 의존하는 spring-security-messaging API 모양을 고정한다.
 * 경로변수 추출이 조용히 비면 모든 구독이 거부되므로 여기서 먼저 드러나게 한다.
 */
class MessageAuthorizationApiTest {

    private static Message<?> subscribe(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken(
                "7", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    @Test
    void 목적지_경로변수를_인가_컨텍스트에서_읽을_수_있다() {
        AtomicReference<String> seen = new AtomicReference<>();

        MessageMatcherDelegatingAuthorizationManager.Builder builder =
                MessageMatcherDelegatingAuthorizationManager.builder();
        builder
                .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}")
                .access((auth, ctx) -> {
                    seen.set(ctx.getVariables().get("chatroomId"));
                    return new AuthorizationDecision(true);
                })
                .anyMessage().denyAll();
        AuthorizationManager<Message<?>> manager = builder.build();

        AuthorizationDecision decision =
                (AuthorizationDecision) manager.authorize(MessageAuthorizationApiTest::user, subscribe("/sub/chatrooms/42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
        assertThat(seen.get()).isEqualTo("42");
    }

    @Test
    void 규칙에_없는_목적지는_기본_거부된다() {
        MessageMatcherDelegatingAuthorizationManager.Builder builder =
                MessageMatcherDelegatingAuthorizationManager.builder();
        builder
                .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}").permitAll()
                .anyMessage().denyAll();
        AuthorizationManager<Message<?>> manager = builder.build();

        AuthorizationDecision decision =
                (AuthorizationDecision) manager.authorize(MessageAuthorizationApiTest::user, subscribe("/sub/notices"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }
}
