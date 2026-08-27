package com.example.springboot_realtimechat.domain.presence.event;

import com.example.springboot_realtimechat.domain.presence.dto.PresenceResponse;
import com.example.springboot_realtimechat.domain.presence.service.PresenceRegistry;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    // 채팅 구독만 (/presence, /typing 접미사 제외)
    private static final Pattern CHAT_DEST = Pattern.compile("^/sub/chatrooms/(\\d+)$");

    private final PresenceRegistry presenceRegistry;
    private final RedisPublisher redisPublisher;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) return;
        Matcher m = CHAT_DEST.matcher(destination);
        if (!m.matches()) return;

        String sessionId = accessor.getSessionId();
        String subId = accessor.getSubscriptionId();
        Long memberId = extractMemberId(event.getUser());
        if (sessionId == null || subId == null || memberId == null) return;

        Long roomId = Long.valueOf(m.group(1));
        presenceRegistry.enterRoom(sessionId, roomId, subId, memberId)
                .ifPresent(this::broadcastRoom);   // 전환 시 이전 방도 갱신
        broadcastRoom(roomId);
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String subId = accessor.getSubscriptionId();
        if (sessionId == null || subId == null) return;
        presenceRegistry.leaveBySubscription(sessionId, subId).ifPresent(this::broadcastRoom);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        presenceRegistry.disconnect(event.getSessionId()).ifPresent(this::broadcastRoom);
    }

    private Long extractMemberId(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getMemberId();
        }
        return null;
    }

    private void broadcastRoom(Long roomId) {
        PresenceResponse roster = new PresenceResponse(
                roomId, new ArrayList<>(presenceRegistry.getRoomOnlineMemberIds(roomId)));
        redisPublisher.publishPresence(roster);
    }
}
