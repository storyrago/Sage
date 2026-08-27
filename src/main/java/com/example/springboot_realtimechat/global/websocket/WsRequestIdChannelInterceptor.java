package com.example.springboot_realtimechat.global.websocket;

import com.example.springboot_realtimechat.global.common.RequestIdFilter;

import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 핸드셰이크 때 세션 속성에 심어둔 추적 ID를 STOMP 프레임 처리 스레드의 MDC에 옮긴다.
 * preSend는 WebSocket 수신 스레드에서, 핸들러 호출(beforeHandle)은 clientInboundChannel
 * 실행자 풀의 다른 스레드에서 돌기 때문에 두 쌍 모두 심고 지워야 한다.
 */
@Component
public class WsRequestIdChannelInterceptor implements ExecutorChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        putMdc(message);
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        putMdc(message);
        return message;
    }

    @Override
    public void afterMessageHandled(
            Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    private void putMdc(Message<?> message) {
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
        String requestId = attributes != null && attributes.get(RequestIdFilter.MDC_KEY) instanceof String value
                ? value
                : SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (requestId != null) {
            MDC.put(RequestIdFilter.MDC_KEY, requestId);
        }
    }
}
