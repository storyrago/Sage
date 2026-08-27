package com.example.springboot_realtimechat.logging;

import com.example.springboot_realtimechat.global.common.RequestIdFilter;
import com.example.springboot_realtimechat.global.websocket.WsRequestIdChannelInterceptor;
import com.example.springboot_realtimechat.global.websocket.WsRequestIdHandshakeInterceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 핸드셰이크 인터셉터가 MDC의 추적 ID를 세션 속성으로 옮기고, 채널 인터셉터가 그 속성을
// STOMP 프레임 처리 스레드의 MDC에 되돌려 심고 지우는지 검증한다.
class WsRequestIdTest {

    private final WsRequestIdHandshakeInterceptor handshakeInterceptor = new WsRequestIdHandshakeInterceptor();
    private final WsRequestIdChannelInterceptor channelInterceptor = new WsRequestIdChannelInterceptor();

    @AfterEach
    void MDC를_비운다() {
        MDC.clear();
    }

    @Test
    void 핸드셰이크_시점에_MDC_값이_있으면_세션_속성에_담긴다() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "handshake-rid");
        Map<String, Object> attributes = new HashMap<>();

        handshakeInterceptor.beforeHandshake(request(), response(), null, attributes);

        assertThat(attributes.get(RequestIdFilter.MDC_KEY)).isEqualTo("handshake-rid");
    }

    @Test
    void 핸드셰이크_시점에_MDC가_비어있으면_새_ID가_담긴다() throws Exception {
        Map<String, Object> attributes = new HashMap<>();

        handshakeInterceptor.beforeHandshake(request(), response(), null, attributes);

        assertThat(attributes.get(RequestIdFilter.MDC_KEY)).isNotNull();
        assertThat((String) attributes.get(RequestIdFilter.MDC_KEY)).isNotBlank();
    }

    @Test
    void preSend_후_MDC에_세션_속성의_추적_ID가_들어간다() {
        Message<byte[]> message = stompMessage();

        channelInterceptor.preSend(message, null);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("handshake-rid");
    }

    @Test
    void beforeHandle_후_MDC에_세션_속성의_추적_ID가_들어간다() {
        Message<byte[]> message = stompMessage();

        channelInterceptor.beforeHandle(message, null, null);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("handshake-rid");
    }

    @Test
    void afterMessageHandled_후_MDC가_비워진다() {
        Message<byte[]> message = stompMessage();
        channelInterceptor.beforeHandle(message, null, null);

        channelInterceptor.afterMessageHandled(message, null, null, null);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void afterSendCompletion_후_MDC가_비워진다() {
        Message<byte[]> message = stompMessage();
        channelInterceptor.preSend(message, null);

        channelInterceptor.afterSendCompletion(message, null, true, null);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 세션_속성이_없으면_STOMP_세션_ID로_대체된다() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("sess-1");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        channelInterceptor.preSend(message, null);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("sess-1");
    }

    private Message<byte[]> stompMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(Map.of(RequestIdFilter.MDC_KEY, "handshake-rid"));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private ServerHttpRequest request() {
        return new ServletServerHttpRequest(new MockHttpServletRequest());
    }

    private ServerHttpResponse response() {
        return new ServletServerHttpResponse(new MockHttpServletResponse());
    }
}
