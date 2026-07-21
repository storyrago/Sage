package com.example.springboot_realtimechat.presence;

import com.example.springboot_realtimechat.dto.PresenceResponse;
import com.example.springboot_realtimechat.redis.RedisPublisher;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
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
    private final RedisPublisher redisPublisher;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Long memberId = extractMemberId(event.getUser());

        if (sessionId == null || memberId == null) {
            return;
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
            broadcastRoster();
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
        redisPublisher.publishPresence(roster);
    }
}
