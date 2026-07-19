package com.example.springboot_realtimechat.presence;

import com.example.springboot_realtimechat.dto.PresenceResponse;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private static final String PRESENCE_DESTINATION = "/sub/presence";

    private final PresenceRegistry presenceRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Long memberId = extractMemberId(event.getUser());

        if (sessionId == null || memberId == null) {
            return; // 인증 안 된 연결은 추적하지 않음
        }

        presenceRegistry.connect(sessionId, memberId);
        broadcastRoster();
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        presenceRegistry.disconnect(event.getSessionId());
        broadcastRoster();
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (PRESENCE_DESTINATION.equals(accessor.getDestination())) {
            broadcastRoster(); // 방금 접속한 클라의 late-join 동기화
        }
    }

    private Long extractMemberId(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getMemberId();
        }
        return null;
    }

    private void broadcastRoster() {
        PresenceResponse roster =
                new PresenceResponse(new ArrayList<>(presenceRegistry.getOnlineMemberIds()));
        messagingTemplate.convertAndSend(PRESENCE_DESTINATION, roster);
    }
}
