package com.example.springboot_realtimechat.presence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceRegistry {

    private static final String SESSION_KEY = "presence:session";  // sessionId -> "roomId|subId|memberId"

    private final StringRedisTemplate redis;

    private static String roomKey(Long roomId) {
        return "presence:room:" + roomId;
    }

    /** 입장/전환. 이전 방이 있고 다르면 그 방에서 제거하고 그 방 id 반환(방송용). */
    public Optional<Long> enterRoom(String sessionId, Long roomId, String subId, Long memberId) {
        Long previousRoom = null;
        Object prev = redis.opsForHash().get(SESSION_KEY, sessionId);
        if (prev != null) {
            Long oldRoom = parseRoom(prev.toString());
            if (oldRoom != null && !oldRoom.equals(roomId)) {
                redis.opsForHash().delete(roomKey(oldRoom), sessionId);
                previousRoom = oldRoom;
            }
        }
        redis.opsForHash().put(roomKey(roomId), sessionId, String.valueOf(memberId));
        redis.opsForHash().put(SESSION_KEY, sessionId, roomId + "|" + subId + "|" + memberId);
        return Optional.ofNullable(previousRoom);
    }

    /** 지정 subId가 세션의 현재 채팅 구독이면 방에서 나감. 나간 방 id 반환. */
    public Optional<Long> leaveBySubscription(String sessionId, String subId) {
        Object cur = redis.opsForHash().get(SESSION_KEY, sessionId);
        if (cur == null) return Optional.empty();
        String[] parts = cur.toString().split("\\|");
        if (parts.length < 2 || !parts[1].equals(subId)) return Optional.empty();
        Long roomId = Long.valueOf(parts[0]);
        redis.opsForHash().delete(roomKey(roomId), sessionId);
        redis.opsForHash().delete(SESSION_KEY, sessionId);
        return Optional.of(roomId);
    }

    /** 접속 종료 → 현재 방에서 제거. 나간 방 id 반환. */
    public Optional<Long> disconnect(String sessionId) {
        Object cur = redis.opsForHash().get(SESSION_KEY, sessionId);
        if (cur == null) return Optional.empty();
        redis.opsForHash().delete(SESSION_KEY, sessionId);
        Long roomId = parseRoom(cur.toString());
        if (roomId == null) return Optional.empty();
        redis.opsForHash().delete(roomKey(roomId), sessionId);
        return Optional.of(roomId);
    }

    public Set<Long> getRoomOnlineMemberIds(Long roomId) {
        return redis.opsForHash().values(roomKey(roomId)).stream()
                .map(v -> Long.valueOf(v.toString()))
                .collect(Collectors.toSet());
    }

    private Long parseRoom(String state) {
        String[] parts = state.split("\\|");
        return parts.length > 0 ? Long.valueOf(parts[0]) : null;
    }

    /**
     * 기동 시 남아 있는 presence:* 항목을 전부 지운다. app이 막 재시작됐다면 이 인스턴스가
     * 들고 있던 WebSocket 세션은 이미 전멸했으므로, 기동 시점의 프레즌스 항목은 전부 유령이다.
     * 실패해도 기동을 막지 않는다 — 삼키고 경고만 남긴다.
     * ponytail: app 인스턴스 1대를 전제로 한 정리다. 2대 이상으로 늘리면 늦게 뜬 인스턴스가
     * 다른 인스턴스의 살아있는 프레즌스를 지운다 — 그때는 세션 단위 TTL 갱신으로 바꿔야 한다.
     */
    public void cleanupStaleEntries() {
        try {
            ScanOptions options = ScanOptions.scanOptions().match("presence:*").build();
            try (Cursor<String> cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    redis.delete(cursor.next());
                }
            }
        } catch (Exception e) {
            log.warn("기동 시 프레즌스 정리 실패", e);
        }
    }
}
