package com.example.springboot_realtimechat.global.websocket;

import com.example.springboot_realtimechat.global.jwt.JwtAuthChannelInterceptor;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtAuthChannelInterceptor jwtAuthChannelInterceptor;
    private final RoomAuthorizationChannelInterceptor roomAuthorizationChannelInterceptor;
    private final WsRequestIdHandshakeInterceptor wsRequestIdHandshakeInterceptor;
    private final WsRequestIdChannelInterceptor wsRequestIdChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry){
        registry.enableSimpleBroker("/sub", "/queue"); // 클라이언트가 구독하는 주소 (/queue: convertAndSendToUser 개인 큐)
        registry.setApplicationDestinationPrefixes("/pub"); // 클라이언트가 보내는 주소
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry){
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(wsRequestIdHandshakeInterceptor);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 순서가 곧 인가다. 토큰 검증으로 사용자를 세운 뒤 SecurityContext를 채우고, 그다음 규칙을 평가한다.
        // 추적 ID는 맨 앞이어야 뒤따르는 인터셉터들의 로그(인가 거부 경고 등)에도 실린다.
        registration.interceptors(
                wsRequestIdChannelInterceptor,
                jwtAuthChannelInterceptor,
                new SecurityContextChannelInterceptor(),
                roomAuthorizationChannelInterceptor
        );
    }
}
