package com.example.springboot_realtimechat.ws;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.springboot_realtimechat.logging.RequestIdFilter;
import com.example.springboot_realtimechat.security.JwtAuthChannelInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtAuthChannelInterceptor가 남기는 인증 실패 로그를 실제 인터셉터 체인으로 검증한다.
 * 추적 ID 인터셉터가 앞에 있어 인증 실패 로그에도 추적 ID가 실리는지가 핵심이다.
 */
@SpringBootTest
@Transactional
class WsAuthLoggingTest {

    @Autowired
    @Qualifier("clientInboundChannel")
    AbstractSubscribableChannel clientInboundChannel;

    private Logger jwtAuthLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        jwtAuthLogger = (Logger) LoggerFactory.getLogger(JwtAuthChannelInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        jwtAuthLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        jwtAuthLogger.detachAppender(appender);
        MDC.clear();
    }

    /** 등록된 인터셉터를 실제 순서대로 통과시킨다. 어느 하나가 프레임을 버리면 null이 나온다. */
    private Message<?> through(Message<?> message) {
        for (ChannelInterceptor interceptor : clientInboundChannel.getInterceptors()) {
            message = interceptor.preSend(message, clientInboundChannel);
            if (message == null) {
                return null;
            }
        }
        return message;
    }

    private Message<?> connectFrame(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(new HashMap<>(Map.of(RequestIdFilter.MDC_KEY, "auth-rid")));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void 토큰_없는_CONNECT는_사유와_추적_ID를_함께_남긴다() {
        assertThatThrownBy(() -> through(connectFrame(null)))
                .isInstanceOf(AccessDeniedException.class);

        ILoggingEvent event = warnEventContaining("NO_TOKEN");
        assertThat(event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY)).isEqualTo("auth-rid");
    }

    @Test
    void 무효_토큰_CONNECT는_INVALID_TOKEN을_남긴다() {
        assertThatThrownBy(() -> through(connectFrame("Bearer not-a-real-token")))
                .isInstanceOf(AccessDeniedException.class);

        warnEventContaining("INVALID_TOKEN");
    }

    private ILoggingEvent warnEventContaining(String needle) {
        List<ILoggingEvent> matches = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(needle))
                .toList();
        assertThat(matches).hasSize(1);
        // 레벨도 함께 본다. 인증 실패가 INFO로 내려가면 경보 대상에서 조용히 빠진다.
        assertThat(matches.get(0).getLevel()).isEqualTo(Level.WARN);
        return matches.get(0);
    }
}
