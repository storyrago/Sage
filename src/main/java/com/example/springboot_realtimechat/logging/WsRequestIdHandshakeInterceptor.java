package com.example.springboot_realtimechat.logging;

import org.slf4j.MDC;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * WS 핸드셰이크(HTTP 업그레이드 요청)는 RequestIdFilter를 지나므로 이 시점 MDC에 추적 ID가 있다.
 * 그 값을 세션 속성에 옮겨, STOMP 프레임 처리 스레드에서도 같은 ID를 MDC에 심을 수 있게 한다.
 */
@Component
public class WsRequestIdHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        attributes.put(RequestIdFilter.MDC_KEY, requestId != null ? requestId : UUID.randomUUID().toString());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }
}
