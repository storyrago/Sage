package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * 인가 규칙을 평가하고 거부를 두 등급으로 나눈다.
 * 인증 실패는 알릴 대상이 없으므로 예외로 세션을 닫고,
 * 인가 실패는 나머지 방이 정상이므로 프레임만 버리고 개인 채널로 사유를 보낸다.
 */
@Slf4j
@Component
public class RoomAuthorizationChannelInterceptor implements ChannelInterceptor {

    private final AuthorizationManager<Message<?>> authorizationManager;
    // SimpMessagingTemplate은 같은 메시징 설정에서 만들어지므로 지연 조회해 순환 의존을 피한다
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;

    public RoomAuthorizationChannelInterceptor(
            AuthorizationManager<Message<?>> authorizationManager,
            ObjectProvider<SimpMessagingTemplate> messagingTemplate) {
        this.authorizationManager = authorizationManager;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        Authentication authentication = authenticationOf(accessor);

        AuthorizationResult result = authorizationManager.authorize(() -> authentication, message);

        // 일치하는 규칙이 없으면 null이 온다. 허용이 아니라 거부로 취급한다.
        if (result != null && result.isGranted()) {
            return message;
        }

        boolean connect = accessor != null && StompCommand.CONNECT.equals(accessor.getCommand());
        if (connect || authentication == null) {
            throw new AccessDeniedException("Access Denied");
        }

        String destination = accessor != null ? accessor.getDestination() : null;
        // 인가 거부는 운영에서 원인을 추적할 수 있어야 한다. 페이로드는 남기지 않는다.
        log.warn("STOMP 인가 거부: command={}, destination={}, principal={}",
                accessor != null ? accessor.getCommand() : null, destination, authentication.getName());
        messagingTemplate.getObject().convertAndSendToUser(
                authentication.getName(),
                "/queue/errors",
                new WsErrorResponse(
                        ErrorCode.NOT_JOINED_ROOM.name(),
                        ErrorCode.NOT_JOINED_ROOM.getMessage(),
                        destination));
        return null;
    }

    private Authentication authenticationOf(StompHeaderAccessor accessor) {
        if (accessor == null) {
            return null;
        }
        Principal user = accessor.getUser();
        return user instanceof Authentication authentication ? authentication : null;
    }
}
