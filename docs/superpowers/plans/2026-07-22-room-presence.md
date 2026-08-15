# 방-스코프 온라인 Presence Implementation Plan

> **실행:** 계획/리뷰=Opus, 구현=Sonnet. 참고 코드는 "이 방향". 체크박스로 추적.

**Goal:** 전역 온라인 → "지금 이 방을 보고 있는 사람만" 온라인으로 교체.

**Architecture:** 참여 신호=채팅 구독 `/sub/chatrooms/{id}`. Redis 방별 Hash + 세션상태 Hash로 입장·전환·랜딩(unsubscribe)·접속종료 처리. 방별 로스터를 `/sub/chatrooms/{id}/presence`로 방송(기존 Redis 릴레이 재사용→멀티서버).

**Tech Stack:** Spring Boot(STOMP 세션 이벤트, Spring Data Redis), JUnit+Mockito, React+TS, Node E2E.

## Global Constraints

- **스키마 변경 없음**(Redis만). Redis 키 `presence:room:{id}`·`presence:session` 추가.
- 참여 신호는 **채팅 구독**(`/sub/chatrooms/{숫자}$`)만. `/presence`·`/typing` 접미사 제외.
- 방송 payload `PresenceResponse{roomId, onlineMemberIds}` → `/sub/chatrooms/{roomId}/presence`.
- 전역 `/sub/presence`·`connect(global)` 제거. `ChatArea` 무변경.
- 프론트 `onPresence(roomId, ids)` — roomId가 현재 방일 때만 반영.

---

### Task 1: 백엔드 — 방-스코프 registry + listener + response + subscriber

**Files:**
- Modify: `dto/PresenceResponse.java`
- Modify: `presence/PresenceRegistry.java` (재작성)
- Modify: `presence/WebSocketEventListener.java` (재작성)
- Modify: `redis/PresenceRedisSubscriber.java`
- Test: `presence/PresenceRegistryTest.java` (재작성, Mockito)

- [ ] **Step 1: PresenceResponse에 roomId 추가**
```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import java.util.List;

@Getter
public class PresenceResponse {
    private final Long roomId;
    private final List<Long> onlineMemberIds;

    public PresenceResponse(Long roomId, List<Long> onlineMemberIds) {
        this.roomId = roomId;
        this.onlineMemberIds = onlineMemberIds;
    }
}
```

- [ ] **Step 2: PresenceRegistry 재작성 (방별 Redis)**
`presence/PresenceRegistry.java`:
```java
package com.example.springboot_realtimechat.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
}
```

- [ ] **Step 3: WebSocketEventListener 재작성 (subscribe/unsubscribe/disconnect)**
`presence/WebSocketEventListener.java`:
```java
package com.example.springboot_realtimechat.presence;

import com.example.springboot_realtimechat.dto.PresenceResponse;
import com.example.springboot_realtimechat.redis.RedisPublisher;
import com.example.springboot_realtimechat.security.CustomUserDetails;
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
```

- [ ] **Step 4: PresenceRedisSubscriber — 방별 목적지로 라우팅**
`redis/PresenceRedisSubscriber.java`의 `convertAndSend` 라인 교체:
```java
            PresenceResponse presence = objectMapper.readValue(message.getBody(), PresenceResponse.class);
            messagingTemplate.convertAndSend("/sub/chatrooms/" + presence.getRoomId() + "/presence", presence);
```

- [ ] **Step 5: PresenceRegistryTest 재작성 (Mockito, CI-safe)**
`src/test/java/.../presence/PresenceRegistryTest.java`:
```java
package com.example.springboot_realtimechat.presence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PresenceRegistryTest {

    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> hashOps;
    @InjectMocks PresenceRegistry registry;

    @Test
    void 방_온라인은_해시값_distinct_Long이다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.values("presence:room:5")).willReturn(List.of("10", "10", "20"));

        assertThat(registry.getRoomOnlineMemberIds(5L)).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 처음_입장하면_이전방_없음_empty() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn(null);

        assertThat(registry.enterRoom("s1", 5L, "sub-1", 10L)).isEmpty();
        verify(hashOps).put("presence:room:5", "s1", "10");
        verify(hashOps).put("presence:session", "s1", "5|sub-1|10");
    }

    @Test
    void 다른방으로_전환하면_이전방에서_제거하고_이전방id반환() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.enterRoom("s1", 7L, "sub-2", 10L)).contains(5L);
        verify(hashOps).delete("presence:room:5", "s1");
        verify(hashOps).put("presence:room:7", "s1", "10");
    }

    @Test
    void 현재_채팅subId면_방에서_나가고_방id반환() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.leaveBySubscription("s1", "sub-1")).contains(5L);
        verify(hashOps).delete("presence:room:5", "s1");
        verify(hashOps).delete("presence:session", "s1");
    }

    @Test
    void 다른_subId_해제는_무시() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.leaveBySubscription("s1", "sub-typing")).isEmpty();
    }

    @Test
    void 접속종료시_현재방에서_제거() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.disconnect("s1")).contains(5L);
        verify(hashOps).delete("presence:room:5", "s1");
        verify(hashOps).delete("presence:session", "s1");
    }
}
```

- [ ] **Step 6: 빌드**
Run: `./gradlew build` → BUILD SUCCESSFUL (유닛테스트 포함).

- [ ] **Step 7: 커밋**
```bash
git add src/main/java/com/example/springboot_realtimechat/dto/PresenceResponse.java \
        src/main/java/com/example/springboot_realtimechat/presence/PresenceRegistry.java \
        src/main/java/com/example/springboot_realtimechat/presence/WebSocketEventListener.java \
        src/main/java/com/example/springboot_realtimechat/redis/PresenceRedisSubscriber.java \
        src/test/java/com/example/springboot_realtimechat/presence/PresenceRegistryTest.java
git commit -m "feat(room-presence): 방-스코프 registry+세션이벤트+방별 방송"
```

---

### Task 2: 프론트 — 방 presence 구독/해제 + App 배선

**Files:**
- Modify: `frontend/src/lib/stomp.ts`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: stomp.ts — 전역 presence 제거 + 방 presence 구독/해제**

`StompClientOptions`의 `onPresence` 시그니처 변경:
```ts
  onPresence?: (roomId: string, onlineMemberIds: string[]) => void;
```

필드: `presenceSubscription`·`PRESENCE_DESTINATION` 제거, `currentRoomPresenceSubscription` 추가, kinds에 `'roompresence'`:
```ts
  private currentChatSubscription?: string;
  private currentTypingSubscription?: string;
  private currentRoomPresenceSubscription?: string;
  private subscriptionKinds = new Map<string, 'chat' | 'typing' | 'roompresence'>();
```

`handleRawMessage`의 CONNECTED 분기에서 `this.subscribePresence();` **제거**. `subscribePresence()` 메서드 삭제.

`subscribe(chatroomId)` 교체(방 presence를 **채팅보다 먼저** 구독):
```ts
  subscribe(chatroomId: string) {
    if (!this.connected) return;
    this.unsubscribeRoom();   // 이전 방 구독 정리

    // 1) 방 presence (내 입장 방송 놓치지 않게 채팅보다 먼저)
    this.currentRoomPresenceSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentRoomPresenceSubscription, 'roompresence');
    this.write('SUBSCRIBE', {
      id: this.currentRoomPresenceSubscription,
      destination: `/sub/chatrooms/${chatroomId}/presence`,
      ack: 'auto',
    });

    // 2) 채팅 (참여 신호)
    this.currentChatSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentChatSubscription, 'chat');
    this.write('SUBSCRIBE', {
      id: this.currentChatSubscription,
      destination: `/sub/chatrooms/${chatroomId}`,
      ack: 'auto',
    });

    // 3) 타이핑
    this.currentTypingSubscription = `sub-${++this.subscriptionId}`;
    this.subscriptionKinds.set(this.currentTypingSubscription, 'typing');
    this.write('SUBSCRIBE', {
      id: this.currentTypingSubscription,
      destination: `/sub/chatrooms/${chatroomId}/typing`,
      ack: 'auto',
    });
  }

  /** 방 나가기(랜딩) — 방 구독 3개 해제. 백엔드가 채팅 unsubscribe로 방에서 제거. */
  unsubscribeRoom() {
    for (const sub of [this.currentChatSubscription, this.currentTypingSubscription, this.currentRoomPresenceSubscription]) {
      if (sub) {
        this.write('UNSUBSCRIBE', { id: sub });
        this.subscriptionKinds.delete(sub);
      }
    }
    this.currentChatSubscription = undefined;
    this.currentTypingSubscription = undefined;
    this.currentRoomPresenceSubscription = undefined;
  }
```

`disconnect()`의 구독 초기화도 3개 반영(있으면 `unsubscribeRoom()` 호출 or 필드 undefined 처리). `presenceSubscription` 참조 제거.

`handleRawMessage`의 MESSAGE 라우팅에서 presence 분기 교체:
```ts
        } else if (kind === 'roompresence') {
          const payload = JSON.parse(frame.body) as { roomId: number | string; onlineMemberIds: Array<number | string> };
          this.options.onPresence?.(String(payload.roomId), payload.onlineMemberIds.map(String));
        } else if (kind === 'typing') {
```

- [ ] **Step 2: App.tsx — onPresence 가드 + 랜딩 시 unsubscribe**

STOMP 옵션의 `onPresence` 교체(roomId 가드):
```ts
        onPresence: (roomId, ids) => {
          if (roomId === selectedChannelRef.current) {
            setOnlineMemberIds(new Set(ids));
          }
        },
```

랜딩(방 나감) 처리 이펙트 추가(다른 useEffect 근처):
```ts
  useEffect(() => {
    if (!selectedChannelId) {
      stompRef.current?.unsubscribeRoom();
      setOnlineMemberIds(new Set());
    }
  }, [selectedChannelId]);
```
(방 전환은 `subscribe()`가 이전 방 정리하므로 별도 처리 불필요. 방→랜딩만 여기서.)

- [ ] **Step 3: tsc + build**
Run: `npm --prefix frontend run lint && npm --prefix frontend run build` → 통과.

- [ ] **Step 4: 커밋**
```bash
git add frontend/src/lib/stomp.ts frontend/src/App.tsx
git commit -m "feat(room-presence): 방 presence 구독/해제 + roomId 가드 배선"
```

---

### Task 3: 멀티서버 E2E

백엔드 2인스턴스(8080·8081) 공유 Redis+DB. 방-스코프 presence 크로스-인스턴스 검증.

- [ ] **Step 1: 백엔드 2개 기동** (JWT_SECRET + ddl-auto=update, 8081은 `--args='--server.port=8081'`).

- [ ] **Step 2: E2E 스크립트** `scratchpad/room_presence_e2e.mjs` — 각 클라가 방 R의 `/sub/chatrooms/R/presence` 구독 + 채팅 `/sub/chatrooms/R` 구독으로 입장. 검증:
  - A(8080) 방R 입장 → A가 R 로스터에 자기 수신.
  - B(8081) 방R 입장 → **A가 [A,B] 수신**(크로스-인스턴스).
  - B 방S로 전환(S 채팅 구독) → A가 R 로스터 [A]로 수신(B 빠짐).
  - B 방R 채팅 UNSUBSCRIBE(랜딩) → 해당 방 로스터에서 빠짐.
  - (STOMP 프레임 직접 작성; subId는 SUBSCRIBE id 헤더.)

- [ ] **Step 3: 실행** `node scratchpad/room_presence_e2e.mjs` → 전부 통과.

- [ ] **Step 4: 인스턴스 종료.**

---

## Self-Review 결과

- **스펙 커버리지:** 방별 Redis+세션상태(Task1) / 세션이벤트 subscribe·unsubscribe·disconnect(Task1) / 방별 방송·roomId(Task1) / 프론트 구독·해제·가드(Task2) / 멀티서버 E2E(Task3) — 전 항목 매핑. 전역 presence 제거·ChatArea 무변경 반영.
- **Placeholder:** 없음(실제 코드/명령). Task3 스크립트는 typing/presence E2E와 동형이라 개요+검증항목 명시.
- **타입 일관성:** `presence:room:{id}`·`presence:session`("roomId|subId|memberId") · `enterRoom/leaveBySubscription/disconnect`(Optional<Long>) · `PresenceResponse{roomId,onlineMemberIds}` · `/sub/chatrooms/{id}/presence` · `onPresence(roomId, ids)` · `CHAT_DEST` 정규식(채팅만) — 일치.
