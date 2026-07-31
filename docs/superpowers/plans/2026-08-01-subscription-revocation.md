# 멤버십 취소 시 구독 회수 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 멤버십이 사라지는 순간 그 회원의 살아 있는 세션에서 해당 방 구독을 서버가 회수한다.

**Architecture:** `SimpUserRegistry`가 사용자 → 세션 → 구독(id·destination)을 들고 있으므로, 회수 대상의 `subscriptionId`를 특정해 그 세션 명의의 `UNSUBSCRIBE` 프레임을 `clientInboundChannel`에 넣는다. 브로커가 구독 테이블에서 그 항목만 지운다. 채널에 직접 넣은 프레임은 `SessionUnsubscribeEvent`를 발생시키지 않으므로 revoker가 같은 프레임으로 이벤트를 직접 발행해 프레즌스와 `SimpUserRegistry`를 함께 갱신한다.

**Tech Stack:** Spring Boot 4.0.5, Spring Security 7.0.4, spring-websocket 7.0.6, JUnit 5, Mockito, H2

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-08-01-subscription-revocation-design.md`
- **주입 프레임에 싣는 헤더는 `simpMessageType=UNSUBSCRIBE`·`simpSessionId`·`simpSubscriptionId`·`destination` 뿐이다.** 세션 속성(`sessionAttributes`)과 `simpUser` 헤더를 복사하지 않는다 — `tokenExpiresAt`가 실리면 만료된 세션의 회수가 `JwtAuthChannelInterceptor`에 막힌다
- **목적지 판정은 완전 일치다.** `startsWith`를 쓰지 않는다. `/sub/chatrooms/3`으로 접두사 판정하면 방 30·31·300의 구독까지 회수된다
- 개인 큐 구독(`/user/queue/unread`, `/user/queue/errors`)은 회수하지 않는다
- 통지는 **회수한 방마다 1건**이다. 세션마다 보내면 같은 토스트가 세션 수만큼 중복된다
- `MessageChannel` 타입 빈이 셋이므로 주입에 **`@Qualifier("clientInboundChannel")`를 반드시 붙인다**
- 스키마 변경 없음. Flyway 마이그레이션을 추가하지 않는다
- 새 의존성 없음
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm run lint && npm test && npm run build`
- 브랜치: develop에서 `feat/subscription-revocation`을 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사를 넣지 않는다

## File Structure

| 파일 | 책임 |
|---|---|
| `global/exception/ErrorCode.java` (수정) | `ROOM_MEMBERSHIP_REVOKED` 추가 |
| `security/RoomSubscriptionRevoker.java` (신규) | 대상 구독 선별 + `UNSUBSCRIBE` 주입 + 이벤트 발행 + 통지 |
| `event/RoomLeftEvent.java` (신규) | `record RoomLeftEvent(Long memberId, Long roomId)` |
| `event/MemberDeletedEvent.java` (신규) | `record MemberDeletedEvent(Long memberId)` |
| `event/SubscriptionRevocationListener.java` (신규) | 두 이벤트를 `AFTER_COMMIT`에서 받아 revoker 호출 |
| `service/ChatRoomMemberService.java` (수정) | `leave()`에서 `RoomLeftEvent` 발행 |
| `service/MemberService.java` (수정) | `delete()`에서 `MemberDeletedEvent` 발행 |
| `frontend/src/App.tsx` (수정) | 회수 코드일 때 토스트 없이 랜딩 복귀 |

---

### Task 1: 회수 컴포넌트

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`
- Create: `src/main/java/com/example/springboot_realtimechat/security/RoomSubscriptionRevoker.java`
- Test: `src/test/java/com/example/springboot_realtimechat/ws/RoomSubscriptionRevokerTest.java` (신규)

**Interfaces:**
- Consumes: `SimpUserRegistry`, `MessageChannel`(clientInboundChannel), `ApplicationEventPublisher`, `SimpMessagingTemplate`, `WsErrorResponse`(기존 레코드 `(String code, String message, String destination)`)
- Produces: `RoomSubscriptionRevoker#revokeRoom(Long memberId, Long roomId)`, `RoomSubscriptionRevoker#revokeAll(Long memberId)` — Task 2의 리스너가 이 둘을 호출한다

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git checkout develop && git pull && git checkout -b feat/subscription-revocation
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/RoomSubscriptionRevokerTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomSubscriptionRevokerTest {

    private SimpUserRegistry userRegistry;
    private MessageChannel clientInboundChannel;
    private ApplicationEventPublisher eventPublisher;
    private SimpMessagingTemplate messagingTemplate;
    private RoomSubscriptionRevoker revoker;

    @BeforeEach
    void setUp() {
        userRegistry = mock(SimpUserRegistry.class);
        clientInboundChannel = mock(MessageChannel.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        when(clientInboundChannel.send(any())).thenReturn(true);
        revoker = new RoomSubscriptionRevoker(
                userRegistry, clientInboundChannel, eventPublisher, messagingTemplate);
    }

    private SimpSubscription subscription(String id, String destination) {
        SimpSubscription subscription = mock(SimpSubscription.class);
        when(subscription.getId()).thenReturn(id);
        when(subscription.getDestination()).thenReturn(destination);
        return subscription;
    }

    private SimpSession session(String id, SimpSubscription... subscriptions) {
        SimpSession session = mock(SimpSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getSubscriptions()).thenReturn(new LinkedHashSet<>(List.of(subscriptions)));
        return session;
    }

    private void online(Long memberId, SimpSession... sessions) {
        SimpUser user = mock(SimpUser.class);
        when(user.getSessions()).thenReturn(new LinkedHashSet<>(List.of(sessions)));
        when(userRegistry.getUser(String.valueOf(memberId))).thenReturn(user);
    }

    /** 채널로 나간 프레임들의 subscriptionId 목록 */
    private List<String> revokedSubscriptionIds() {
        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.captor();
        verify(clientInboundChannel, org.mockito.Mockito.atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream()
                .map(m -> StompHeaderAccessor.wrap(m).getSubscriptionId())
                .toList();
    }

    @Test
    void 대상_방의_구독_3종만_회수한다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/3/typing"),
                subscription("sub-3", "/sub/chatrooms/3/presence"),
                subscription("sub-4", "/sub/chatrooms/9"),
                subscription("sub-5", "/user/queue/unread")));

        revoker.revokeRoom(7L, 3L);

        assertThat(revokedSubscriptionIds()).containsExactlyInAnyOrder("sub-1", "sub-2", "sub-3");
    }

    @Test
    void 방_3_회수가_방_30을_건드리지_않는다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/30"),
                subscription("sub-3", "/sub/chatrooms/30/typing")));

        revoker.revokeRoom(7L, 3L);

        assertThat(revokedSubscriptionIds()).containsExactly("sub-1");
    }

    @Test
    void 회수한_구독마다_구독해제_이벤트를_발행한다() {
        online(7L, session("s1", subscription("sub-1", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        ArgumentCaptor<Object> captor = ArgumentCaptor.captor();
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SessionUnsubscribeEvent.class);

        SessionUnsubscribeEvent event = (SessionUnsubscribeEvent) captor.getValue();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(accessor.getSessionId()).isEqualTo("s1");
        assertThat(accessor.getSubscriptionId()).isEqualTo("sub-1");
    }

    @Test
    void 회수한_방마다_통지를_한_건_보낸다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/3/typing")));

        revoker.revokeRoom(7L, 3L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.captor();
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq("/queue/errors"), payload.capture());

        WsErrorResponse sent = (WsErrorResponse) payload.getValue();
        assertThat(sent.code()).isEqualTo("ROOM_MEMBERSHIP_REVOKED");
        assertThat(sent.destination()).isEqualTo("/sub/chatrooms/3");
        assertThat(sent.message()).isNotBlank();
    }

    @Test
    void revokeAll은_모든_방을_회수하고_개인_큐는_남긴다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/3"),
                subscription("sub-2", "/sub/chatrooms/9/typing"),
                subscription("sub-3", "/user/queue/unread"),
                subscription("sub-4", "/user/queue/errors")));

        revoker.revokeAll(7L);

        assertThat(revokedSubscriptionIds()).containsExactlyInAnyOrder("sub-1", "sub-2");
    }

    @Test
    void 여러_세션을_모두_처리한다() {
        online(7L,
                session("s1", subscription("sub-1", "/sub/chatrooms/3")),
                session("s2", subscription("sub-9", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        assertThat(revokedSubscriptionIds()).containsExactlyInAnyOrder("sub-1", "sub-9");
    }

    @Test
    void 세션이_없으면_아무것도_하지_않는다() {
        when(userRegistry.getUser("7")).thenReturn(null);

        revoker.revokeRoom(7L, 3L);

        verify(clientInboundChannel, never()).send(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 프레임이_폐기되면_이벤트를_발행하지_않는다() {
        when(clientInboundChannel.send(any())).thenReturn(false);
        online(7L, session("s1", subscription("sub-1", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 주입_프레임에_세션_속성을_싣지_않는다() {
        online(7L, session("s1", subscription("sub-1", "/sub/chatrooms/3")));

        revoker.revokeRoom(7L, 3L);

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.captor();
        verify(clientInboundChannel).send(captor.capture());
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(captor.getValue());
        assertThat(accessor.getSessionAttributes()).isNull();
        assertThat(accessor.getUser()).isNull();
    }

    @Test
    void 알_수_없는_목적지는_회수하지_않는다() {
        online(7L, session("s1",
                subscription("sub-1", "/sub/chatrooms/abc"),
                subscription("sub-2", "/sub/notices")));

        revoker.revokeAll(7L);

        verify(clientInboundChannel, never()).send(any());
    }
}
```

> `ArgumentCaptor.captor()`는 Mockito 5의 제네릭 친화 팩터리다. 컴파일이 안 되면 기존 테스트가 쓰는 `ArgumentCaptor.forClass(...)` 형태로 바꾼다.
>
> import `java.util.Set`은 위 코드에서 쓰지 않으므로 넣지 않는다.

- [ ] **Step 3: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*RoomSubscriptionRevokerTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class RoomSubscriptionRevoker`

- [ ] **Step 4: 오류 코드 추가**

`ErrorCode.java`의 `// ChatRoomMember` 블록에서 `NOT_JOINED_ROOM` 다음 줄에 추가한다.

```java
    ROOM_MEMBERSHIP_REVOKED(403, "채팅방에서 나갔어요."),
```

- [ ] **Step 5: 회수 컴포넌트 구현**

`src/main/java/com/example/springboot_realtimechat/security/RoomSubscriptionRevoker.java`:

```java
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
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests '*RoomSubscriptionRevokerTest*'
```

기대: PASS. 실패하면 `build/reports/tests/test/index.html`에서 어느 단언인지 확인한다.

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java src/main/java/com/example/springboot_realtimechat/security/RoomSubscriptionRevoker.java src/test/java/com/example/springboot_realtimechat/ws/RoomSubscriptionRevokerTest.java
git commit -m "feat(authz): 멤버십 취소 시 방 구독을 회수하는 컴포넌트 추가"
```

---

### Task 2: 트리거 배선

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/event/RoomLeftEvent.java`
- Create: `src/main/java/com/example/springboot_realtimechat/event/MemberDeletedEvent.java`
- Create: `src/main/java/com/example/springboot_realtimechat/event/SubscriptionRevocationListener.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationListenerTest.java` (신규)

**Interfaces:**
- Consumes: `RoomSubscriptionRevoker#revokeRoom`, `RoomSubscriptionRevoker#revokeAll` (Task 1)
- Produces: `RoomLeftEvent(Long memberId, Long roomId)`, `MemberDeletedEvent(Long memberId)` — 다른 곳에서 이 이벤트를 구독하지 않는다

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationListenerTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.event.MemberDeletedEvent;
import com.example.springboot_realtimechat.event.RoomLeftEvent;
import com.example.springboot_realtimechat.event.SubscriptionRevocationListener;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SubscriptionRevocationListenerTest {

    private RoomSubscriptionRevoker revoker;
    private SubscriptionRevocationListener listener;

    @BeforeEach
    void setUp() {
        revoker = mock(RoomSubscriptionRevoker.class);
        listener = new SubscriptionRevocationListener(revoker);
    }

    @Test
    void 방을_나가면_그_방_구독을_회수한다() {
        listener.onRoomLeft(new RoomLeftEvent(7L, 3L));

        verify(revoker).revokeRoom(7L, 3L);
    }

    @Test
    void 회원이_탈퇴하면_모든_방_구독을_회수한다() {
        listener.onMemberDeleted(new MemberDeletedEvent(7L));

        verify(revoker).revokeAll(7L);
    }

    @Test
    void 회수가_실패해도_예외를_밖으로_내보내지_않는다() {
        doThrow(new IllegalStateException("boom")).when(revoker).revokeRoom(7L, 3L);

        assertThatCode(() -> listener.onRoomLeft(new RoomLeftEvent(7L, 3L)))
                .doesNotThrowAnyException();
    }
}
```

> 롤백 시 회수가 일어나지 않는 것은 `@TransactionalEventListener(AFTER_COMMIT)` 애너테이션이 보장한다. `@SpringBootTest @Transactional` 테스트는 커밋 자체가 없어 이 동작을 구분하지 못하므로, 여기서는 리스너 단위 동작만 고정하고 애너테이션은 Step 3의 코드로 확정한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*SubscriptionRevocationListenerTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class RoomLeftEvent`

- [ ] **Step 3: 이벤트와 리스너 작성**

`src/main/java/com/example/springboot_realtimechat/event/RoomLeftEvent.java`:

```java
package com.example.springboot_realtimechat.event;

/** 회원이 방을 나갔다. 그 방의 구독을 회수하는 근거다. */
public record RoomLeftEvent(Long memberId, Long roomId) {
}
```

`src/main/java/com/example/springboot_realtimechat/event/MemberDeletedEvent.java`:

```java
package com.example.springboot_realtimechat.event;

/** 회원이 탈퇴했다. 그 회원의 모든 방 구독을 회수하는 근거다. */
public record MemberDeletedEvent(Long memberId) {
}
```

`src/main/java/com/example/springboot_realtimechat/event/SubscriptionRevocationListener.java`:

```java
package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionRevocationListener {

    private final RoomSubscriptionRevoker revoker;

    // 커밋된 뒤에만 회수한다. 트랜잭션 안에서 회수하면 롤백되어도 구독은 이미 지워진다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomLeft(RoomLeftEvent event) {
        try {
            revoker.revokeRoom(event.memberId(), event.roomId());
        } catch (Exception e) {
            log.warn("방 구독 회수 실패: memberId={}, roomId={}", event.memberId(), event.roomId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberDeleted(MemberDeletedEvent event) {
        try {
            revoker.revokeAll(event.memberId());
        } catch (Exception e) {
            log.warn("회원 구독 회수 실패: memberId={}", event.memberId(), e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests '*SubscriptionRevocationListenerTest*'
```

기대: PASS — 3 tests

- [ ] **Step 5: `ChatRoomMemberService`에서 이벤트 발행**

필드에 발행기를 추가한다(`@RequiredArgsConstructor`이므로 필드 선언만 추가하면 된다).

```java
    private final ApplicationEventPublisher eventPublisher;
```

import 추가:

```java
import com.example.springboot_realtimechat.event.RoomLeftEvent;
import org.springframework.context.ApplicationEventPublisher;
```

`leave()`의 `chatRoomMemberRepository.delete(chatRoomMember);` 다음 줄에 추가한다.

```java
        eventPublisher.publishEvent(new RoomLeftEvent(memberId, chatRoomId));
```

- [ ] **Step 6: `MemberService`에서 이벤트 발행**

`MemberService`에는 `eventPublisher` 필드가 이미 있다. import만 추가한다.

```java
import com.example.springboot_realtimechat.event.MemberDeletedEvent;
```

`delete()`의 `memberRepository.delete(member);` 다음 줄에 추가한다.

```java
        eventPublisher.publishEvent(new MemberDeletedEvent(id));
```

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/event/ src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java src/main/java/com/example/springboot_realtimechat/service/MemberService.java src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationListenerTest.java
git commit -m "feat(authz): 방 나가기와 회원 탈퇴에 구독 회수를 연결"
```

---

### Task 3: 조립 상태 통합 테스트

**Files:**
- Test: `src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationIntegrationTest.java` (신규)

**Interfaces:**
- Consumes: Task 1·2의 산출물 전부. 새 프로덕션 코드를 만들지 않는다

**배경:** 단위 테스트는 `SimpUserRegistry`를 mock 한다. 실제 컨텍스트에서 주입 프레임이 인터셉터 3개를 통과해 브로커 구독 테이블까지 도달하는지는 아직 아무도 확인하지 않았다.

`SimpUserRegistry`는 `SessionConnectedEvent`·`SessionSubscribeEvent`로만 채워지고 두 이벤트는 실제 소켓 프레임에서만 발행된다. 테스트에서는 두 이벤트를 직접 발행해 레지스트리를 채운다.

- [ ] **Step 1: 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationIntegrationTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 컨텍스트에서 회수 프레임이 인터셉터를 통과해 브로커 구독 테이블까지 도달하는지 본다.
 * 인가 관련 빈은 mock 하지 않는다.
 */
@SpringBootTest
class SubscriptionRevocationIntegrationTest {

    private static final String SESSION_ID = "test-session";
    private static final Long MEMBER_ID = 7L;

    @Autowired RoomSubscriptionRevoker revoker;
    @Autowired ApplicationEventPublisher eventPublisher;

    // 빈 정의의 반환 타입이 AbstractBrokerMessageHandler라 타입만으로는 주입되지 않을 수 있다.
    @Autowired @Qualifier("simpleBrokerMessageHandler") AbstractBrokerMessageHandler brokerMessageHandler;

    private SimpleBrokerMessageHandler broker() {
        return (SimpleBrokerMessageHandler) brokerMessageHandler;
    }

    private Authentication user() {
        CustomUserDetails details = new CustomUserDetails(MEMBER_ID, "u@test.com");
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private Message<byte[]> frame(StompCommand command, String destination, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setUser(user());
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 브로커 구독 테이블에 그 목적지의 구독이 남아 있는지 */
    private boolean subscribed(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return !broker().getSubscriptionRegistry().findSubscriptions(probe).isEmpty();
    }

    /** 인바운드 채널은 비동기라 반영까지 잠깐 기다린다. */
    private void awaitUnsubscribed(String destination) throws InterruptedException {
        for (int i = 0; i < 50 && subscribed(destination); i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void 회수하면_브로커_구독_테이블에서_사라진다() throws InterruptedException {
        // 브로커에 구독을 등록한다(동기)
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/3", "sub-1");
        broker().getSubscriptionRegistry().registerSubscription(subscribe);

        // SimpUserRegistry를 채운다. 이 두 이벤트는 실제 소켓 프레임에서만 나오므로 직접 발행한다.
        eventPublisher.publishEvent(new SessionConnectedEvent(
                this, frame(StompCommand.CONNECTED, null, null), user()));
        eventPublisher.publishEvent(new SessionSubscribeEvent(this, subscribe, user()));

        assertThat(subscribed("/sub/chatrooms/3")).isTrue();

        revoker.revokeRoom(MEMBER_ID, 3L);
        awaitUnsubscribed("/sub/chatrooms/3");

        assertThat(subscribed("/sub/chatrooms/3")).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew test --tests '*SubscriptionRevocationIntegrationTest*'
```

기대: PASS.

실패하면 원인을 아래 순서로 좁힌다.

1. `assertThat(subscribed(...)).isTrue()`에서 실패 → 구독 등록 자체가 안 된 것이다. `registerSubscription`에 넘긴 프레임의 `sessionId`·`subscriptionId`·`destination`을 확인한다.
2. 마지막 단언에서 실패 → 회수 프레임이 브로커에 도달하지 않았다. `RoomSubscriptionRevoker`에 로그를 넣어 `send()` 반환값과 대상 구독 수를 확인한다. 대상이 0개면 `SimpUserRegistry`가 비어 있는 것이므로 `SessionConnectedEvent` 발행이 먼저인지 확인한다.

- [ ] **Step 3: 회수가 실제로 동작하는지 뮤테이션으로 확인**

`RoomSubscriptionRevoker#unsubscribe`의 `clientInboundChannel.send(frame)` 호출을 잠시 주석 처리하고 다시 실행한다.

```bash
./gradlew test --tests '*SubscriptionRevocationIntegrationTest*'
```

기대: **FAIL**. 통과하면 이 테스트는 아무것도 검증하지 않는 것이므로 원인을 찾는다. 확인 후 주석을 되돌린다.

- [ ] **Step 4: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationIntegrationTest.java
git commit -m "test(authz): 회수 프레임이 브로커 구독 테이블에 반영되는지 고정"
```

---

### Task 4: 프론트 회수 코드 분기

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `WsAuthzError { code, message, destination? }` (기존)
- Produces: 없음

**배경:** 회수 통지는 그 회원의 **모든 세션**에 배달된다. 나가기를 실행한 본인 탭에도 도착하므로, 지금 코드대로면 자기가 성공시킨 동작에 오류 토스트가 뜬다.

- [ ] **Step 1: 현재 배선 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && grep -n "onAuthzError" -A 8 frontend/src/App.tsx
```

`notify(...)`를 무조건 호출하는 것을 확인한다.

- [ ] **Step 2: 코드일 때 토스트를 건너뛴다**

`onAuthzError` 배선을 아래로 바꾼다.

```tsx
        onAuthzError: ({ code, message, destination }) => {
          // 세션은 살아있고 특정 목적지만 거부된 것이므로 재연결하지 않는다.
          // 회수는 본인이 방을 나간 결과이므로 오류로 알리지 않는다.
          if (code !== 'ROOM_MEMBERSHIP_REVOKED') {
            notify(message || '이 채널에 접근할 수 없어요.');
          }
          const deniedRoom = roomIdFromDestination(destination);
          if (deniedRoom && deniedRoom === selectedChannelRef.current) {
            setSelectedChannelId('');   // 볼 수 없는 방에 머무르지 않는다
          }
        },
```

- [ ] **Step 3: 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "feat(frontend): 구독 회수 통지는 오류 토스트 없이 랜딩으로 되돌림"
```

---

### Task 5: 최종 검증과 PR

**Files:** 없음 (검증만)

- [ ] **Step 1: 백엔드 전체 테스트**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test --rerun-tasks
```

기대: BUILD SUCCESSFUL. `--rerun-tasks`를 쓰는 이유는 `UP-TO-DATE` 캐시가 실행을 건너뛰면 검증이 아니기 때문이다.

- [ ] **Step 2: 프론트 전체 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 3: 스키마 변경이 없는지 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git diff develop --stat -- 'src/main/resources/db/migration'
```

기대: 출력 없음

- [ ] **Step 4: 설계 §7 자동 테스트 목록과 대조**

| §7 항목 | 테스트 |
|---|---|
| 대상 방의 구독 3종에만 회수 | `RoomSubscriptionRevokerTest.대상_방의_구독_3종만_회수한다` |
| 방 3 회수가 방 30을 건드리지 않음 | `RoomSubscriptionRevokerTest.방_3_회수가_방_30을_건드리지_않는다` |
| 대상 회원의 세션만 처리 | `RoomSubscriptionRevokerTest.여러_세션을_모두_처리한다`(같은 회원만 조회) |
| `revokeAll`이 개인 큐를 남김 | `RoomSubscriptionRevokerTest.revokeAll은_모든_방을_회수하고_개인_큐는_남긴다` |
| 구독 해제 이벤트 발행 | `RoomSubscriptionRevokerTest.회수한_구독마다_구독해제_이벤트를_발행한다` |
| 통지 1건과 destination | `RoomSubscriptionRevokerTest.회수한_방마다_통지를_한_건_보낸다` |
| 인터셉터를 통과해 브로커까지 도달 | `SubscriptionRevocationIntegrationTest.회수하면_브로커_구독_테이블에서_사라진다` |
| 세션이 없을 때 통과 | `RoomSubscriptionRevokerTest.세션이_없으면_아무것도_하지_않는다` |
| 리스너가 revoker를 부름 | `SubscriptionRevocationListenerTest` 3건 |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 5: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓴다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** 회수 프레임에 세션 속성을 복사하면 만료된 세션의 회수가 조용히 실패하고, 목적지 판정을 접두사로 바꾸면 방 3 회수가 방 30까지 지운다. 둘 다 테스트가 없으면 드러나지 않는 종류다.

```bash
git push -u origin feat/subscription-revocation
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 6: 배포 후 실측 항목을 PR에 남긴다**

프론트에 나가기·탈퇴 UI가 없으므로 API를 직접 호출해 유발한다. PR 본문의 "구현 노트 / 알려진 한계"에 아래를 남긴다.

- 탭 두 개로 같은 방을 열고 `DELETE /api/chatrooms/{id}/members` 호출 → 두 탭이 랜딩으로 돌아가고 오류 토스트가 뜨지 않는지
- 나간 방의 새 메시지가 더 이상 도착하지 않는지(개발자 도구 WS 프레임)
- 같은 방의 다른 사용자 화면에서 나간 사람이 접속자 목록에서 사라지는지
- 나가지 않은 다른 방 구독과 소켓이 유지되는지

---

## Self-Review

**스펙 커버리지 (설계 §2~§7):**

| 요구 | 태스크 |
|---|---|
| R1 세션을 닫지 않고 구독만 회수 | Task 1 |
| R2 `SessionUnsubscribeEvent` 직접 발행 | Task 1 |
| R3 구독 3종·완전 일치 판정 | Task 1 |
| R4 트리거 두 개, 개인 큐 제외 | Task 1(제외), Task 2(트리거) |
| R5 `AFTER_COMMIT` | Task 2 |
| R6 새 오류 코드와 통지 | Task 1(백엔드), Task 4(프론트) |
| §4 헤더 제약·세션 속성 미복사 | Task 1 Step 5, 테스트로 고정 |
| §5 오류 처리(폐기·예외·세션 없음) | Task 1, Task 2 |
| §7 자동 테스트 | Task 1·2·3, Task 5 Step 4에서 대조 |
| §7 배포 후 실측 | Task 5 Step 6 |
