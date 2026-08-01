package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 멤버십이 취소된 회원의 방 구독을 회수한다.
 * 세션은 닫지 않고, 그 세션 명의의 UNSUBSCRIBE를 인바운드 채널에 넣어 브로커가 구독만 지우게 한다.
 */
@Slf4j
@Component
public class RoomSubscriptionRevoker {

    // /sub/chatrooms/3, /sub/chatrooms/3/typing, /sub/chatrooms/3/presence 만 대상이다.
    // 접두사 판정을 쓰면 방 3 회수가 방 30까지 지운다.
    private static final Pattern ROOM_DESTINATION =
            Pattern.compile("^/sub/chatrooms/(\\d+)(?:/typing|/presence)?$");

    private final SimpUserRegistry userRegistry;
    private final MessageChannel clientInboundChannel;
    private final ApplicationEventPublisher eventPublisher;
    private final SimpMessagingTemplate messagingTemplate;

    public RoomSubscriptionRevoker(
            SimpUserRegistry userRegistry,
            @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel,
            ApplicationEventPublisher eventPublisher,
            SimpMessagingTemplate messagingTemplate) {
        this.userRegistry = userRegistry;
        this.clientInboundChannel = clientInboundChannel;
        this.eventPublisher = eventPublisher;
        this.messagingTemplate = messagingTemplate;
    }

    /** 방 하나의 구독 3종을 회수한다. */
    public void revokeRoom(Long memberId, Long roomId) {
        revoke(memberId, room -> room.equals(roomId));
    }

    /** 그 회원의 모든 방 구독을 회수한다. 개인 큐 구독은 남긴다. */
    public void revokeAll(Long memberId) {
        revoke(memberId, room -> true);
    }

    private void revoke(Long memberId, Predicate<Long> roomFilter) {
        SimpUser user = userRegistry.getUser(String.valueOf(memberId));
        if (user == null) {
            return;
        }

        Set<Long> revokedRooms = new LinkedHashSet<>();
        for (SimpSession session : List.copyOf(user.getSessions())) {
            for (SimpSubscription subscription : List.copyOf(session.getSubscriptions())) {
                Long roomId = roomIdOf(subscription.getDestination());
                if (roomId == null || !roomFilter.test(roomId)) {
                    continue;
                }
                if (unsubscribe(session.getId(), subscription.getId(), subscription.getDestination())) {
                    revokedRooms.add(roomId);
                }
            }
        }

        // 개인 목적지 전송은 그 회원의 모든 세션에 배달된다. 방마다 한 번만 보낸다.
        revokedRooms.forEach(roomId -> notifyRevoked(memberId, roomId));
    }

    private boolean unsubscribe(String sessionId, String subscriptionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        if (!clientInboundChannel.send(frame)) {
            log.warn("구독 회수 프레임이 폐기됨: sessionId={}, subscriptionId={}", sessionId, subscriptionId);
            return false;
        }

        // 채널에 직접 넣은 프레임은 SessionUnsubscribeEvent를 만들지 않는다.
        // 이 이벤트로 프레즌스와 사용자 레지스트리가 갱신되므로 직접 발행한다.
        eventPublisher.publishEvent(new SessionUnsubscribeEvent(this, frame));
        return true;
    }

    private void notifyRevoked(Long memberId, Long roomId) {
        messagingTemplate.convertAndSendToUser(
                String.valueOf(memberId),
                "/queue/errors",
                new WsErrorResponse(
                        ErrorCode.ROOM_MEMBERSHIP_REVOKED.name(),
                        ErrorCode.ROOM_MEMBERSHIP_REVOKED.getMessage(),
                        "/sub/chatrooms/" + roomId));
    }

    private Long roomIdOf(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matched = ROOM_DESTINATION.matcher(destination);
        return matched.matches() ? Long.valueOf(matched.group(1)) : null;
    }
}
