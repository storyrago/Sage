# PR 1 — WebSocket 인가 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** STOMP 프레임에 기본 거부 인가를 적용해, 방 멤버만 그 방을 구독하고 전송할 수 있게 한다.

**Architecture:** Spring Security의 선언적 메시지 인가 규칙(`MessageMatcherDelegatingAuthorizationManager`)은 그대로 쓰되, 인터셉터는 직접 구현한다. 스톡 `AuthorizationChannelInterceptor`는 무조건 예외를 던져 세션을 닫으므로 "인증 실패는 세션 종료, 인가 실패는 프레임 폐기 + 개인 오류 채널"이라는 2단 거부를 만들 수 없다. `@EnableWebSocketSecurity`도 쓰지 않는다 — CSRF 인터셉터를 끌 수 없고 우리 JWT 인터셉터보다 먼저 실행된다.

**Tech Stack:** Spring Boot 4.0.5, Spring Security 7.0.4, `spring-security-messaging`(신규), JUnit 5, H2

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-07-31-room-authorization-design.md` §4(거부 모델)·§5(WS 인가)·§10(검증)
- **`@EnableWebSocketSecurity`를 쓰지 않는다.** 붙이면 `XorCsrfChannelInterceptor`가 무조건 등록되고(CSRF는 이 애노테이션으로 끌 수 없다) 자동 설정이 `@Order(HIGHEST_PRECEDENCE + 100)`이라 JWT 인터셉터보다 먼저 인가가 평가된다
- **스톡 `AuthorizationChannelInterceptor`를 쓰지 않는다.** 규칙(`AuthorizationManager`)만 쓰고 인터셉터는 직접 구현한다
- 인터셉터 순서는 **JWT → `SecurityContextChannelInterceptor` → 인가**. 어긋나면 규칙 내용과 무관하게 전부 거부된다
- 규칙은 first-match-wins이고 마지막은 반드시 `anyMessage().denyAll()`
- 목적지는 **명시적으로 나열한다.** `/sub/chatrooms/{id}/**` 같은 와일드카드 규칙을 쓰지 않는다
- 새 의존성은 `org.springframework.security:spring-security-messaging` **하나뿐이고 버전을 명시하지 않는다**(BOM이 관리)
- **Task 1 실측 결과(확정, 이후 태스크가 그대로 쓴다):** `AuthorizationManager`의 호출 메서드는 `check(...)`가 아니라 `authorize(Supplier<? extends Authentication>, T)`이고 반환 타입은 `AuthorizationResult`(nullable). `AuthorizationDecision implements AuthorizationResult`이므로 `access(...)`에서는 `new AuthorizationDecision(boolean)`을 반환하고, 호출부는 `result != null && result.isGranted()`로 판정한다. `MessageAuthorizationContext.getVariables()`는 `{chatroomId}`를 정상으로 채운다(실측 확인)
- **`authorize()`는 일치하는 규칙이 없으면 `null`을 반환한다**(실측 확인). `null`을 허용으로 해석하면 안 된다 — `anyMessage().denyAll()`이 지워지거나 순서가 바뀌는 순간 전부 열린다. 판정은 항상 `result != null && result.isGranted()` 형태로만 쓴다
- 스키마 변경 없음. Flyway 마이그레이션을 추가하지 않는다
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm run lint && npm test && npm run build`
- 브랜치: develop에서 `feat/ws-authorization`을 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사("누락됐다", "그래서 깨져 있었다")를 넣지 않는다
- 테스트는 `src/test/resources/application.yaml`(H2, Flyway 비활성)을 쓴다. 새 설정값을 추가하면 여기에도 더미값을 넣어야 `@Value` 플레이스홀더 에러가 안 난다

## File Structure

| 파일 | 책임 |
|---|---|
| `build.gradle` (수정) | `spring-security-messaging` 의존성 |
| `security/RoomAccess.java` (신규) | **멤버십 판정 단일 진실 공급원.** `isMember(memberId, roomId)` 하나 |
| `repository/ChatRoomMemberRepository.java` (수정) | id 기반 존재 조회 |
| `config/WebSocketAuthorizationConfig.java` (신규) | 인가 규칙 빈. 목적지별 규칙과 와일드카드 거부 매처 |
| `security/JwtAuthChannelInterceptor.java` (신규) | `WebSocketConfig`의 익명 인터셉터를 이름 있는 컴포넌트로 분리. 토큰 검증 + 만료 시각 보관 |
| `security/RoomAuthorizationChannelInterceptor.java` (신규) | 규칙 평가 + 2단 거부 |
| `dto/WsErrorResponse.java` (신규) | 개인 오류 채널 페이로드 |
| `config/WebSocketConfig.java` (수정) | 인터셉터 3개 순서 배선 |
| `security/JwtTokenProvider.java` (수정) | 토큰 만료 시각 조회 |
| `frontend/src/lib/stomp.ts` (수정) | 프레임 본문 파싱 가드 |
| `frontend/src/App.tsx` (수정) | 인가 거부의 `destination`으로 방 단위 처리 |

---

### Task 1: 의존성 추가와 API 사실 확인

**Files:**
- Modify: `build.gradle`
- Test: `src/test/java/com/example/springboot_realtimechat/ws/MessageAuthorizationApiTest.java` (신규)

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `spring-security-messaging`이 classpath에 있음. 이후 태스크가 쓰는 API 모양이 테스트로 고정됨

**배경:** 설계 §5-6이 실측하라고 지정한 세 가지가 있다. `MessageAuthorizationContext.getVariables()`가 `{chatroomId}`를 실제로 채우는지(매처 래핑 시 경로변수가 유실되는 이슈 이력이 있다), `AuthorizationManager`의 호출 메서드 시그니처, 그리고 의존성 추가 후 애플리케이션 컨텍스트가 뜨는지다. 이걸 테스트로 박아두면 이후 태스크가 추측 없이 진행된다.

또한 `@EnableWebSocketSecurity`는 `spring-security-config` jar 안에 있어 **의존성 없이도 컴파일이 통과하고 기동할 때 `NoClassDefFoundError`로 죽는다.** develop push가 CD 자동 배포를 트리거하므로 이 실패 모드는 CI에서 잡아야 한다.

- [ ] **Step 1: 의존성 추가**

`build.gradle`의 `//spring security` 주석 아래, `spring-boot-starter-security` 다음 줄에 추가한다.

```gradle
	implementation 'org.springframework.security:spring-security-messaging'
```

버전을 쓰지 않는다. `spring-security-bom`이 7.0.4로 관리한다.

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/MessageAuthorizationApiTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인가 규칙이 의존하는 spring-security-messaging API 모양을 고정한다.
 * 경로변수 추출이 조용히 비면 모든 구독이 거부되므로 여기서 먼저 드러나게 한다.
 */
class MessageAuthorizationApiTest {

    private static Message<?> subscribe(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken(
                "7", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    @Test
    void 목적지_경로변수를_인가_컨텍스트에서_읽을_수_있다() {
        AtomicReference<String> seen = new AtomicReference<>();

        MessageMatcherDelegatingAuthorizationManager.Builder builder =
                MessageMatcherDelegatingAuthorizationManager.builder();
        builder
                .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}")
                .access((auth, ctx) -> {
                    seen.set(ctx.getVariables().get("chatroomId"));
                    return new AuthorizationDecision(true);
                })
                .anyMessage().denyAll();
        AuthorizationManager<Message<?>> manager = builder.build();

        AuthorizationResult decision =
                manager.authorize(MessageAuthorizationApiTest::user, subscribe("/sub/chatrooms/42"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
        assertThat(seen.get()).isEqualTo("42");
    }

    @Test
    void 규칙에_없는_목적지는_기본_거부된다() {
        MessageMatcherDelegatingAuthorizationManager.Builder builder =
                MessageMatcherDelegatingAuthorizationManager.builder();
        builder
                .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}").permitAll()
                .anyMessage().denyAll();
        AuthorizationManager<Message<?>> manager = builder.build();

        AuthorizationResult decision =
                manager.authorize(MessageAuthorizationApiTest::user, subscribe("/sub/notices"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*MessageAuthorizationApiTest*'
```

기대: 컴파일 실패 — `package org.springframework.security.messaging... does not exist` (의존성을 아직 Gradle이 못 읽었을 경우) 또는 테스트 실패.

의존성을 먼저 추가했으므로 여기서 통과할 수도 있다. **통과하면 그것이 곧 실측 결과다** — 다음 스텝으로 넘어가되 보고서에 "실패 확인 단계에서 이미 통과함"이라고 명시한다.

- [ ] **Step 4: 실측 결과를 확정한다**

```bash
./gradlew test --tests '*MessageAuthorizationApiTest*'
```

기대: PASS.

**실패했다면 그것이 이 태스크의 산출물이다.** 다음 중 어느 것인지 보고하고 멈춘다:
- `manager.check(...)`가 없다 → 실제 메서드명(`authorize` 등)을 확인해 테스트와 보고서에 기록
- `seen.get()`이 `null`이다 → 경로변수 추출이 동작하지 않음. 이후 태스크의 멤버십 판정 방식을 바꿔야 하므로 즉시 escalate
- `builder()` 정적 메서드가 없다 → 실제 생성 방법을 확인해 기록

- [ ] **Step 5: 컨텍스트 로딩 테스트 확인**

기존 `src/test/java/com/example/springboot_realtimechat/SpringbootRealtimechatApplicationTests.java`가 컨텍스트를 띄운다. 의존성 추가 후에도 뜨는지 본다.

```bash
./gradlew test --tests '*SpringbootRealtimechatApplicationTests*'
```

기대: PASS. 이 테스트가 `NoClassDefFoundError` 류의 기동 실패를 CI에서 잡는 그물이다.

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add build.gradle src/test/java/com/example/springboot_realtimechat/ws/MessageAuthorizationApiTest.java
git commit -m "test(authz): 메시지 인가 API 동작을 테스트로 고정"
```

---

### Task 2: 멤버십 판정 단일 진실 공급원

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/security/RoomAccess.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/RoomAccessTest.java` (신규)

**Interfaces:**
- Consumes: `ChatRoomMemberRepository`
- Produces: `RoomAccess#isMember(Long memberId, Long chatRoomId): boolean` — 이후 모든 인가 판정이 이것만 호출한다. PR 2의 REST 검사도 여기로 모은다

**배경:** 지금 "방 멤버인가"를 판단하는 코드가 `MessageService`와 `ChatRoomMemberService`에 흩어져 있고, 전부 `existsByMemberAndChatRoom(Member, ChatRoom)`이라 엔티티를 먼저 로드해야 한다. 인가는 프레임마다 도는 경로이므로 id만으로 인덱스를 한 번 치는 조회가 필요하다. `chatroom_members`에 `(member_id, chatroom_id)` unique 제약이 이미 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/RoomAccessTest.java`:

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RoomAccessTest {

    @Autowired RoomAccess roomAccess;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;

    Member joined;
    Member outsider;
    ChatRoom room;

    @BeforeEach
    void setUp() {
        joined = memberRepository.save(new Member("joined@test.com", null, "참여자"));
        outsider = memberRepository.save(new Member("outsider@test.com", null, "비참여자"));
        room = chatRoomRepository.save(new ChatRoom("방"));
        chatRoomMemberRepository.save(new ChatRoomMember(joined, room));
    }

    @Test
    void 참여한_방이면_true() {
        assertThat(roomAccess.isMember(joined.getId(), room.getId())).isTrue();
    }

    @Test
    void 참여하지_않은_방이면_false() {
        assertThat(roomAccess.isMember(outsider.getId(), room.getId())).isFalse();
    }

    @Test
    void 없는_방이면_false() {
        assertThat(roomAccess.isMember(joined.getId(), 999999L)).isFalse();
    }

    @Test
    void 없는_회원이면_false() {
        assertThat(roomAccess.isMember(999999L, room.getId())).isFalse();
    }

    @Test
    void null_인자는_false() {
        assertThat(roomAccess.isMember(null, room.getId())).isFalse();
        assertThat(roomAccess.isMember(joined.getId(), null)).isFalse();
        assertThat(roomAccess.isMember(null, null)).isFalse();
    }
}
```

> `Member` 생성자 시그니처를 먼저 확인한다. `src/main/java/com/example/springboot_realtimechat/domain/Member.java`를 읽고, 위 `new Member(...)` 호출이 실제 생성자와 다르면 **테스트를 실제 시그니처에 맞춰 고친다**. 기존 테스트(`src/test/java/.../service/ServiceFlowTest.java` 등)가 회원을 어떻게 만드는지 참고한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*RoomAccessTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class RoomAccess`

- [ ] **Step 3: 리포지토리 메서드 추가**

`ChatRoomMemberRepository`의 `existsByMemberAndChatRoom` 다음 줄에 추가한다.

```java
    boolean existsByMemberIdAndChatRoomId(Long memberId, Long chatRoomId);
```

Spring Data가 `member.id`와 `chatRoom.id`로 해석한다. 엔티티를 로드하지 않고 unique 인덱스를 한 번 친다.

- [ ] **Step 4: `RoomAccess` 구현**

`src/main/java/com/example/springboot_realtimechat/security/RoomAccess.java`:

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "이 회원이 이 방의 멤버인가"를 판단하는 유일한 지점.
 * REST와 WebSocket이 같은 답을 쓰도록 판정을 한 곳에 모은다.
 */
@Component
@RequiredArgsConstructor
public class RoomAccess {

    private final ChatRoomMemberRepository chatRoomMemberRepository;

    @Transactional(readOnly = true)
    public boolean isMember(Long memberId, Long chatRoomId) {
        if (memberId == null || chatRoomId == null) {
            return false;
        }
        return chatRoomMemberRepository.existsByMemberIdAndChatRoomId(memberId, chatRoomId);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*RoomAccessTest*'
```

기대: PASS — 5 tests

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/RoomAccess.java src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java src/test/java/com/example/springboot_realtimechat/security/RoomAccessTest.java
git commit -m "feat(authz): 방 멤버십 판정을 단일 컴포넌트로 분리"
```

---

### Task 3: 인가 규칙 빈

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/config/WebSocketAuthorizationConfig.java`
- Test: `src/test/java/com/example/springboot_realtimechat/ws/WebSocketAuthorizationRulesTest.java` (신규)

**Interfaces:**
- Consumes: `RoomAccess#isMember(Long, Long)` (Task 2)
- Produces: `AuthorizationManager<Message<?>>` 빈 하나. Task 5의 인터셉터가 주입받는다

**배경:** 설계 §5-1의 규칙표를 코드로 옮긴다. 순서가 곧 보안이다 — 넓은 규칙이 위에 오면 아래 좁은 규칙이 실행되지 않고, 실패 방향이 "조용한 허용"이라 리뷰에서 드러나지 않는다.

와일드카드 거부가 맨 앞인 이유: 구독 destination은 브로커에서 **패턴으로 취급**된다. `SUBSCRIBE /sub/chatrooms/*`는 매처가 단일 방으로 보고 통과시키지만 브로커는 패턴으로 등록해 전 방 메시지를 배달한다. 이건 인가 판단이 아니라 입력 검증이므로 멤버십 조회보다 앞에 둔다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/WebSocketAuthorizationRulesTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 규칙 자체를 검증한다. 소켓 없이 프레임을 만들어 판정만 본다.
 * 인가는 "되는 것"보다 "안 되는 것"을 고정하는 것이 중요하다.
 */
class WebSocketAuthorizationRulesTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long JOINED_ROOM = 1L;
    private static final Long OTHER_ROOM = 2L;

    private AuthorizationManager<Message<?>> manager;

    @BeforeEach
    void setUp() {
        RoomAccess roomAccess = mock(RoomAccess.class);
        when(roomAccess.isMember(eq(MEMBER_ID), eq(JOINED_ROOM))).thenReturn(true);
        when(roomAccess.isMember(eq(MEMBER_ID), eq(OTHER_ROOM))).thenReturn(false);
        manager = new WebSocketAuthorizationConfig().messageAuthorizationManager(roomAccess);
    }

    private static Message<?> frame(SimpMessageType type, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Supplier<Authentication> loggedIn() {
        CustomUserDetails details = new CustomUserDetails(MEMBER_ID, "u@test.com");
        Authentication auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        return () -> auth;
    }

    private static Supplier<Authentication> anonymous() {
        return () -> null;
    }

    private boolean granted(Supplier<Authentication> auth, Message<?> message) {
        AuthorizationResult result = manager.authorize(auth, message);
        return result != null && result.isGranted();
    }

    @Test
    void 멤버는_방을_구독한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1/typing"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1/presence"))).isTrue();
    }

    @Test
    void 비멤버는_방을_구독하지_못한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/2"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/2/typing"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/2/presence"))).isFalse();
    }

    @Test
    void 멤버는_방에_전송한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/1/messages"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/1/typing"))).isTrue();
    }

    @Test
    void 비멤버는_방에_전송하지_못한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/2/messages"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/chatrooms/2/typing"))).isFalse();
    }

    @Test
    void 와일드카드_목적지는_멤버여도_거부한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/**"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/*"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/queue/**"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/?"))).isFalse();
    }

    @Test
    void 개인_큐는_인증되면_구독한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/user/queue/unread"))).isTrue();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/user/queue/errors"))).isTrue();
    }

    @Test
    void 개인_큐_직접_구독은_거부한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/queue/unread"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/queue/errors"))).isFalse();
    }

    @Test
    void 규칙에_없는_목적지는_거부한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.SUBSCRIBE, "/sub/notices"))).isFalse();
        assertThat(granted(loggedIn(), frame(SimpMessageType.MESSAGE, "/pub/admin/broadcast"))).isFalse();
    }

    @Test
    void 미인증_CONNECT는_거부한다() {
        assertThat(granted(anonymous(), frame(SimpMessageType.CONNECT, null))).isFalse();
    }

    @Test
    void 인증된_CONNECT는_허용한다() {
        assertThat(granted(loggedIn(), frame(SimpMessageType.CONNECT, null))).isTrue();
    }

    @Test
    void 연결_종료_계열은_미인증이어도_허용한다() {
        assertThat(granted(anonymous(), frame(SimpMessageType.DISCONNECT, null))).isTrue();
        assertThat(granted(anonymous(), frame(SimpMessageType.UNSUBSCRIBE, null))).isTrue();
        assertThat(granted(anonymous(), frame(SimpMessageType.HEARTBEAT, null))).isTrue();
    }

    @Test
    void 미인증은_방을_구독하지_못한다() {
        assertThat(granted(anonymous(), frame(SimpMessageType.SUBSCRIBE, "/sub/chatrooms/1"))).isFalse();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*WebSocketAuthorizationRulesTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class WebSocketAuthorizationConfig`

- [ ] **Step 3: 규칙 빈 구현**

`src/main/java/com/example/springboot_realtimechat/config/WebSocketAuthorizationConfig.java`:

```java
package com.example.springboot_realtimechat.config;

import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.util.matcher.MessageMatcher;

/**
 * STOMP 프레임 인가 규칙. 위에서부터 처음 일치하는 규칙이 판정하고, 마지막은 기본 거부다.
 * 규칙을 쓰지 않은 목적지는 막힌 채로 시작한다.
 */
@Configuration
public class WebSocketAuthorizationConfig {

    @Bean
    public AuthorizationManager<Message<?>> messageAuthorizationManager(RoomAccess roomAccess) {
        AuthorizationManager<MessageAuthorizationContext<?>> roomMember = roomMember(roomAccess);

        MessageMatcherDelegatingAuthorizationManager.Builder messages =
                MessageMatcherDelegatingAuthorizationManager.builder();

        messages
                // 브로커가 구독 목적지를 패턴으로 취급하므로, 와일드카드는 규칙 평가 이전에 막는다
                .matchers(wildcardDestination()).denyAll()

                .simpTypeMatchers(SimpMessageType.CONNECT).authenticated()
                .simpTypeMatchers(SimpMessageType.DISCONNECT,
                                  SimpMessageType.UNSUBSCRIBE,
                                  SimpMessageType.HEARTBEAT).permitAll()

                .simpSubscribeDestMatchers("/user/queue/unread", "/user/queue/errors").authenticated()

                .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}",
                                           "/sub/chatrooms/{chatroomId}/typing",
                                           "/sub/chatrooms/{chatroomId}/presence").access(roomMember)

                .simpMessageDestMatchers("/pub/chatrooms/{chatroomId}/messages",
                                         "/pub/chatrooms/{chatroomId}/typing").access(roomMember)

                .anyMessage().denyAll();

        return messages.build();
    }

    private AuthorizationManager<MessageAuthorizationContext<?>> roomMember(RoomAccess roomAccess) {
        return (authentication, context) -> {
            Long roomId = parseLongOrNull(context.getVariables().get("chatroomId"));
            Long memberId = memberIdOf(authentication.get());
            return new AuthorizationDecision(roomAccess.isMember(memberId, roomId));
        };
    }

    /** 목적지에 패턴 문자가 들어오면 거부한다. 정상 클라이언트는 리터럴 목적지만 보낸다. */
    private MessageMatcher<Object> wildcardDestination() {
        return message -> {
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            return destination != null && (destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0);
        };
    }

    private static Long parseLongOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long memberIdOf(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails details)) {
            return null;
        }
        return details.getMemberId();
    }
}
```

> `MessageMatcher`가 함수형 인터페이스가 아니어서 람다가 컴파일되지 않으면, 익명 클래스로 바꾸고 실제 메서드 시그니처를 보고서에 기록한다. `MessageMatcher`의 정확한 패키지가 다르면(`org.springframework.security.messaging.util.matcher.MessageMatcher`) IDE/컴파일러가 알려주는 실제 경로를 쓴다.

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests '*WebSocketAuthorizationRulesTest*'
```

기대: PASS — 12 tests

**`granted(loggedIn(), "/sub/chatrooms/1")`이 false로 나오면** 경로변수 추출이 안 되는 것이다. Task 1의 실측 테스트를 다시 돌려 확인하고 escalate 한다.

- [ ] **Step 5: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. 이 시점에는 규칙 빈만 존재하고 아무도 쓰지 않으므로 기존 동작이 바뀌지 않는다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/config/WebSocketAuthorizationConfig.java src/test/java/com/example/springboot_realtimechat/ws/WebSocketAuthorizationRulesTest.java
git commit -m "feat(authz): STOMP 목적지별 인가 규칙 정의"
```

---

### Task 4: JWT 인터셉터 분리와 체인 배선

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/config/WebSocketConfig.java`

**Interfaces:**
- Consumes: `JwtTokenProvider`
- Produces: `JwtAuthChannelInterceptor` 빈. Task 5의 인가 인터셉터가 이것 **뒤에** 등록된다

**배경:** 지금 토큰 검증이 `WebSocketConfig.configureClientInboundChannel` 안의 익명 `ChannelInterceptor`에 들어 있다. 인가 인터셉터를 순서대로 붙이려면 이름 있는 빈이어야 하고, Task 6의 만료 검사도 여기에 들어간다.

**이 태스크는 동작을 바꾸지 않는다.** 같은 로직을 옮기고 `SecurityContextChannelInterceptor`를 추가할 뿐이다.

- [ ] **Step 1: 현재 코드 확인**

```bash
sed -n '38,75p' src/main/java/com/example/springboot_realtimechat/config/WebSocketConfig.java
```

익명 `ChannelInterceptor`의 `preSend`가 CONNECT일 때만 토큰을 검증하고 `accessor.setUser(...)`를 호출하는 것을 확인한다.

- [ ] **Step 2: 인터셉터를 컴포넌트로 분리**

`src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java`:

```java
package com.example.springboot_realtimechat.security;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/** STOMP CONNECT의 Authorization 헤더를 검증해 세션 사용자로 세운다. */
@Component
@RequiredArgsConstructor
public class JwtAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authorizationHeader = accessor.getFirstNativeHeader("Authorization");

            if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                String token = authorizationHeader.substring(7);

                if (jwtTokenProvider.validateToken(token)) {
                    Long memberId = jwtTokenProvider.getMemberId(token);
                    String email = jwtTokenProvider.getEmail(token);

                    CustomUserDetails userDetails = new CustomUserDetails(memberId, email);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());

                    accessor.setUser(authentication);
                }
            }
        }

        return message;
    }
}
```

- [ ] **Step 3: `WebSocketConfig`에서 배선을 교체**

`WebSocketConfig`의 `configureClientInboundChannel` 전체를 아래로 바꾸고, 익명 클래스와 이제 쓰이지 않는 import를 지운다. 생성자 주입 필드도 `JwtTokenProvider`에서 `JwtAuthChannelInterceptor`로 바꾼다.

```java
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 순서가 곧 인가다. 토큰 검증으로 사용자를 세운 뒤 SecurityContext를 채우고, 그다음 규칙을 평가한다.
        registration.interceptors(
                jwtAuthChannelInterceptor,
                new SecurityContextChannelInterceptor()
        );
    }
```

필요한 import:

```java
import com.example.springboot_realtimechat.security.JwtAuthChannelInterceptor;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
```

`SecurityContextChannelInterceptor`는 `simpUser`를 `SecurityContextHolder`에 채운다. 인가 자체에는 필수가 아니지만 이후 `@MessageMapping`에서 현재 사용자를 참조할 수 있게 한다.

- [ ] **Step 4: 동작이 바뀌지 않았는지 확인**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. 이 태스크는 리팩터링이므로 기존 테스트가 그대로 통과해야 한다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java src/main/java/com/example/springboot_realtimechat/config/WebSocketConfig.java
git commit -m "refactor(ws): 토큰 검증 인터셉터를 컴포넌트로 분리하고 보안 컨텍스트 전파 추가"
```

---

### Task 5: 인가 인터셉터와 2단 거부

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/dto/WsErrorResponse.java`
- Create: `src/main/java/com/example/springboot_realtimechat/security/RoomAuthorizationChannelInterceptor.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/config/WebSocketConfig.java`
- Test: `src/test/java/com/example/springboot_realtimechat/ws/RoomAuthorizationInterceptorTest.java` (신규)

**Interfaces:**
- Consumes: `AuthorizationManager<Message<?>>` (Task 3), `SimpMessagingTemplate`
- Produces: `RoomAuthorizationChannelInterceptor` 빈. `WsErrorResponse(String code, String message, String destination)` 레코드

**배경:** 설계 §4 D1·D2. 인증 실패는 세션을 닫고, 인가 실패는 프레임만 버리고 개인 오류 채널로 사유를 보낸다.

스톡 `AuthorizationChannelInterceptor`를 쓸 수 없는 이유: 무조건 `AccessDeniedException`을 던지고, `ChannelInterceptor`는 다음 인터셉터를 감싸는 구조가 아니라 뒤에서 던진 예외를 앞에서 잡을 수 없다. `preSend`에서 예외가 나가면 `StompSubProtocolHandler`가 세션을 `PROTOCOL_ERROR`(1002)로 닫는다 — 구독 하나가 아니라 소켓 전체가 끊긴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/RoomAuthorizationInterceptorTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomAuthorizationChannelInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomAuthorizationInterceptorTest {

    private AuthorizationManager<Message<?>> manager;
    private SimpMessagingTemplate messagingTemplate;
    private RoomAuthorizationChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        manager = mock(AuthorizationManager.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(messagingTemplate);
        interceptor = new RoomAuthorizationChannelInterceptor(manager, provider);
    }

    private Message<?> frame(StompCommand command, String destination, Authentication user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Authentication loggedIn() {
        CustomUserDetails details = new CustomUserDetails(7L, "u@test.com");
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private void decide(boolean granted) {
        when(manager.authorize(any(), any())).thenReturn(new AuthorizationDecision(granted));
    }

    @Test
    void 허용되면_프레임을_그대로_통과시킨다() {
        decide(true);
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/1", loggedIn());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 인가_실패는_프레임을_버리고_개인_큐로_사유를_보낸다() {
        decide(false);
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/2", loggedIn());

        assertThat(interceptor.preSend(message, channel)).isNull();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq("/queue/errors"), payload.capture());

        WsErrorResponse sent = (WsErrorResponse) payload.getValue();
        assertThat(sent.code()).isEqualTo("NOT_JOINED_ROOM");
        assertThat(sent.destination()).isEqualTo("/sub/chatrooms/2");
        assertThat(sent.message()).isNotBlank();
    }

    @Test
    void 미인증_CONNECT_거부는_예외로_세션을_닫는다() {
        decide(false);
        Message<?> message = frame(StompCommand.CONNECT, null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 사용자가_없는_거부는_알릴_대상이_없으므로_예외로_닫는다() {
        decide(false);
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/1", null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(AccessDeniedException.class);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 인가_실패해도_세션은_유지된다_예외를_던지지_않는다() {
        decide(false);
        Message<?> message = frame(StompCommand.SEND, "/pub/chatrooms/2/messages", loggedIn());

        assertThat(interceptor.preSend(message, channel)).isNull();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*RoomAuthorizationInterceptorTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class RoomAuthorizationChannelInterceptor`

- [ ] **Step 3: 오류 페이로드 정의**

`src/main/java/com/example/springboot_realtimechat/dto/WsErrorResponse.java`:

```java
package com.example.springboot_realtimechat.dto;

/** 개인 오류 채널(/user/queue/errors) 페이로드. destination으로 어느 구독이 거부됐는지 특정한다. */
public record WsErrorResponse(String code, String message, String destination) {
}
```

- [ ] **Step 4: 인가 인터셉터 구현**

`src/main/java/com/example/springboot_realtimechat/security/RoomAuthorizationChannelInterceptor.java`:

```java
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
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
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
```

> `ErrorCode.getMessage()`가 없으면 실제 접근자 이름을 `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`에서 확인해 맞춘다.

`authentication.getName()`이 `String.valueOf(memberId)`인 것은 `CustomUserDetails.getUsername()`이 그렇게 구현돼 있기 때문이다. 기존 `RedisSubscriber`가 `convertAndSendToUser(String.valueOf(member.getId()), "/queue/unread", ...)`로 보내는 것과 같은 키다.

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*RoomAuthorizationInterceptorTest*'
```

기대: PASS — 5 tests

- [ ] **Step 6: 체인에 등록**

`WebSocketConfig.configureClientInboundChannel`을 아래로 바꾸고, 생성자 주입에 `RoomAuthorizationChannelInterceptor`를 추가한다.

```java
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 순서가 곧 인가다. 토큰 검증으로 사용자를 세운 뒤 SecurityContext를 채우고, 그다음 규칙을 평가한다.
        registration.interceptors(
                jwtAuthChannelInterceptor,
                new SecurityContextChannelInterceptor(),
                roomAuthorizationChannelInterceptor
        );
    }
```

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL.

**여기서 기존 테스트가 깨지면 인가가 실제로 걸린 것이다.** 깨진 테스트가 "비멤버가 하던 동작"을 검증하고 있었는지 확인하고, 그렇다면 테스트를 현실에 맞게 고친다. 멤버인데도 거부된다면 인터셉터 순서를 의심한다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/dto/WsErrorResponse.java src/main/java/com/example/springboot_realtimechat/security/RoomAuthorizationChannelInterceptor.java src/main/java/com/example/springboot_realtimechat/config/WebSocketConfig.java src/test/java/com/example/springboot_realtimechat/ws/RoomAuthorizationInterceptorTest.java
git commit -m "feat(authz): STOMP 인가 인터셉터와 개인 오류 채널 전송 추가"
```

---

### Task 6: 세션 수명 중 토큰 만료 강제

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/JwtTokenProvider.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java`
- Test: `src/test/java/com/example/springboot_realtimechat/ws/WsTokenExpiryTest.java` (신규)

**Interfaces:**
- Consumes: `JwtAuthChannelInterceptor` (Task 4)
- Produces: `JwtTokenProvider#getExpiresAt(String token): Long` — epoch millis, 파싱 실패 시 null

**배경:** 설계 §5-4. 지금은 CONNECT 때 한 번만 검증하므로, 한 번 붙은 소켓은 토큰이 만료된 뒤에도 계속 산다. 로그아웃해도 실시간 메시지가 계속 오는 상태가 남는다.

서명 검증이나 DB 조회를 반복하지 않는다. CONNECT 때 만료 시각을 세션 속성에 넣고, 이후 프레임마다 정수 비교 한 번만 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/ws/WsTokenExpiryTest.java`:

```java
package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.security.JwtAuthChannelInterceptor;
import com.example.springboot_realtimechat.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WsTokenExpiryTest {

    private static final String SECRET = "test-secret-key-for-jwt-authentication-1234567890";

    private JwtAuthChannelInterceptor interceptor;
    private JwtTokenProvider provider;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3600000L);
        interceptor = new JwtAuthChannelInterceptor(provider);
    }

    private Message<?> frame(StompCommand command, Map<String, Object> sessionAttributes, String bearer) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionAttributes(sessionAttributes);
        if (bearer != null) {
            accessor.setNativeHeader("Authorization", "Bearer " + bearer);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void CONNECT가_만료_시각을_세션에_기록한다() {
        Map<String, Object> attrs = new HashMap<>();
        String token = provider.createAccessToken(7L, "u@test.com");

        interceptor.preSend(frame(StompCommand.CONNECT, attrs, token), channel);

        Object expiresAt = attrs.get("tokenExpiresAt");
        assertThat(expiresAt).isInstanceOf(Long.class);
        assertThat((Long) expiresAt).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void 만료_전_프레임은_통과한다() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tokenExpiresAt", System.currentTimeMillis() + 60_000L);
        Message<?> message = frame(StompCommand.SEND, attrs, null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 만료된_세션의_프레임은_거부한다() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tokenExpiresAt", System.currentTimeMillis() - 1L);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, attrs, null), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 만료_기록이_없으면_통과시킨다() {
        Map<String, Object> attrs = new HashMap<>();
        Message<?> message = frame(StompCommand.SEND, attrs, null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void 만료_시각을_토큰에서_읽는다() {
        String token = provider.createAccessToken(7L, "u@test.com");
        Long expiresAt = provider.getExpiresAt(token);

        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void 잘못된_토큰의_만료_시각은_null() {
        assertThat(provider.getExpiresAt("not-a-token")).isNull();
    }
}
```

> `JwtTokenProvider`의 생성자 인자 순서와 타입을 `src/main/java/com/example/springboot_realtimechat/security/JwtTokenProvider.java`에서 확인하고, 다르면 테스트를 실제 시그니처에 맞춘다.
>
> 만료 기록이 없을 때 통과시키는 이유: CONNECT 이전 프레임이나 세션 속성이 아직 없는 경로에서 정상 트래픽을 막지 않기 위해서다. 미인증 차단은 Task 3의 CONNECT 규칙이 담당한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*WsTokenExpiryTest*'
```

기대: 컴파일 실패 — `cannot find symbol: method getExpiresAt(java.lang.String)`

- [ ] **Step 3: 만료 시각 조회 추가**

`JwtTokenProvider`의 `getEmail` 다음에 추가한다.

```java
    /** 토큰의 만료 시각(epoch millis). 파싱할 수 없으면 null. */
    public Long getExpiresAt(String token) {
        try {
            return parseClaims(token).getExpiration().getTime();
        } catch (Exception e) {
            return null;
        }
    }
```

> `parseClaims`가 이 클래스의 실제 내부 메서드명이 아니면, `getMemberId`/`getEmail`이 클레임을 읽는 방식을 그대로 따라 쓴다. 파싱 실패 시 예외를 삼키고 null을 반환하는 것만 지킨다.

- [ ] **Step 4: 인터셉터에 만료 검사 추가**

`JwtAuthChannelInterceptor.preSend`를 아래로 바꾼다. CONNECT 분기에서 만료 시각을 기록하고, 그 밖의 프레임에서는 기록된 값과 현재 시각을 비교한다.

```java
    private static final String EXPIRES_AT = "tokenExpiresAt";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authorizationHeader = accessor.getFirstNativeHeader("Authorization");

            if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                String token = authorizationHeader.substring(7);

                if (jwtTokenProvider.validateToken(token)) {
                    Long memberId = jwtTokenProvider.getMemberId(token);
                    String email = jwtTokenProvider.getEmail(token);

                    CustomUserDetails userDetails = new CustomUserDetails(memberId, email);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());

                    accessor.setUser(authentication);

                    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                    Long expiresAt = jwtTokenProvider.getExpiresAt(token);
                    if (sessionAttributes != null && expiresAt != null) {
                        sessionAttributes.put(EXPIRES_AT, expiresAt);
                    }
                }
            }
            return message;
        }

        // 연결 이후 프레임: 서명 검증 없이 기록된 만료 시각만 비교한다
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null
                && sessionAttributes.get(EXPIRES_AT) instanceof Long expiresAt
                && System.currentTimeMillis() >= expiresAt) {
            throw new AccessDeniedException("Access Denied");
        }

        return message;
    }
```

추가 import:

```java
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*WsTokenExpiryTest*'
```

기대: PASS — 6 tests

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/JwtTokenProvider.java src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java src/test/java/com/example/springboot_realtimechat/ws/WsTokenExpiryTest.java
git commit -m "feat(ws): 세션 수명 중 토큰 만료를 프레임마다 확인"
```

---

### Task 7: 프론트 프레임 파싱 가드

**Files:**
- Modify: `frontend/src/lib/stomp.ts`

**Interfaces:**
- Consumes: 없음
- Produces: 없음 (견고성 수정)

**배경:** `handleRawMessage`가 프레임마다 `JSON.parse(frame.body)`를 가드 없이 한다. 본문이 깨지면 예외가 `parseFrames(...).forEach(...)` 밖으로 나가 **같은 배치의 나머지 프레임(채팅 메시지 포함)이 통째로 유실**된다. PR 1이 `/user/queue/errors`로 실제 페이로드를 보내기 시작하므로, 서버가 새 페이로드를 내보내는 이번 PR에서 함께 막는다.

- [ ] **Step 1: 현재 코드 확인**

```bash
cd frontend && sed -n '145,190p' src/lib/stomp.ts
```

`MESSAGE` 분기에서 각 종류마다 `JSON.parse(frame.body)`를 따로 호출하는 것을 확인한다.

- [ ] **Step 2: 파싱을 한 번만 하고 실패를 가둔다**

`handleRawMessage`의 `MESSAGE` 블록 전체를 아래로 바꾼다. 각 분기가 개별로 파싱하던 것을 앞에서 한 번 파싱하고, 실패하면 그 프레임만 건너뛴다.

```ts
      if (frame.command === 'MESSAGE' && frame.body) {
        // 본문 파싱 실패가 forEach 밖으로 나가면 같은 배치의 다른 프레임까지 유실된다
        let payload: unknown;
        try {
          payload = JSON.parse(frame.body);
        } catch {
          console.error('[STOMP] 본문 파싱 실패:', frame.headers.destination ?? frame.headers.subscription);
          return;
        }

        const kind = this.subscriptionKinds.get(frame.headers.subscription);
        if (kind === 'roompresence') {
          const p = payload as { roomId: number | string; onlineMemberIds: Array<number | string> };
          this.options.onPresence?.(String(p.roomId), p.onlineMemberIds.map(String));
        } else if (kind === 'typing') {
          const p = payload as { chatroomId: number | string; memberId: number | string; nickname: string; typing: boolean };
          this.options.onTyping?.({
            chatroomId: String(p.chatroomId),
            memberId: String(p.memberId),
            nickname: p.nickname,
            typing: p.typing,
          });
        } else if (kind === 'unread') {
          this.options.onUnread?.(payload as { chatroomId: number; messageId: number });
        } else if (kind === 'authzerror') {
          this.options.onAuthzError?.(payload as WsAuthzError);
        } else {
          this.options.onMessage(payload as BackendMessage);
        }
        return;
      }
```

`return`은 `forEach` 콜백 안이므로 **그 프레임만 건너뛰고 다음 프레임은 계속 처리된다.**

- [ ] **Step 3: 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0. 기존 13개 테스트가 그대로 통과한다.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/lib/stomp.ts
git commit -m "fix(frontend): 프레임 본문 파싱 실패가 같은 배치의 다른 프레임에 영향을 주지 않게 함"
```

---

### Task 8: 인가 거부를 방 단위로 처리

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `WsAuthzError { code: string; message: string; destination?: string }` (PR 0에서 정의됨)
- Produces: 없음

**배경:** 설계 §4 D2가 `destination`을 싣기로 한 이유는 어느 구독이 거부됐는지 특정하기 위해서다. 지금 `onAuthzError`는 `message`만 꺼내 전역 토스트로 띄운다. 거부된 방이 지금 보고 있는 방이면 그 화면에 남을 이유가 없다 — 입장 실패와 같은 처리를 한다.

- [ ] **Step 1: 현재 배선 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && grep -n "onAuthzError" -A 4 frontend/src/App.tsx
```

`notify(message || '이 채널에 접근할 수 없어요.')` 한 줄인 것을 확인한다.

- [ ] **Step 2: 목적지에서 방 id를 뽑는 헬퍼 추가**

`App.tsx`의 컴포넌트 바깥(다른 최상위 헬퍼들 옆)에 추가한다.

```tsx
// /sub/chatrooms/{id}, /sub/chatrooms/{id}/typing, /pub/chatrooms/{id}/messages 등에서 방 id를 뽑는다
const ROOM_DESTINATION = /^\/(?:sub|pub)\/chatrooms\/(\d+)(?:\/|$)/;

function roomIdFromDestination(destination?: string): string | null {
  if (!destination) return null;
  const matched = ROOM_DESTINATION.exec(destination);
  return matched ? matched[1] : null;
}
```

- [ ] **Step 3: 콜백 교체**

`onAuthzError` 배선을 아래로 바꾼다.

```tsx
        onAuthzError: ({ message, destination }) => {
          // 세션은 살아있고 특정 목적지만 거부된 것이므로 재연결하지 않는다.
          notify(message || '이 채널에 접근할 수 없어요.');
          const deniedRoom = roomIdFromDestination(destination);
          if (deniedRoom && deniedRoom === selectedChannelRef.current) {
            setSelectedChannelId('');   // 볼 수 없는 방에 머무르지 않는다
          }
        },
```

- [ ] **Step 4: 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/App.tsx
git commit -m "feat(frontend): 인가 거부된 방에서 랜딩으로 되돌림"
```

---

### Task 9: 최종 검증과 PR

**Files:** 없음 (검증만)

**Interfaces:**
- Consumes: Task 1~8 전부
- Produces: PR

- [ ] **Step 1: 백엔드 전체 테스트**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 2: 프론트 전체 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 3: 스키마 변경이 없는지 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git diff develop --stat -- 'src/main/resources/db/migration'
```

기대: 출력 없음. 이 PR은 마이그레이션을 추가하지 않는다.

- [ ] **Step 4: 설계 §10의 거부 경로 테스트가 모두 있는지 대조**

설계 문서 §10의 목록과 실제 테스트를 대조한다.

| §10 항목 | 테스트 |
|---|---|
| 미인증 CONNECT 거부 | `WebSocketAuthorizationRulesTest.미인증_CONNECT는_거부한다` |
| 비멤버 방 구독 3종 거부 | `WebSocketAuthorizationRulesTest.비멤버는_방을_구독하지_못한다` |
| 비멤버 전송 2종 거부 | `WebSocketAuthorizationRulesTest.비멤버는_방에_전송하지_못한다` |
| 와일드카드 거부 | `WebSocketAuthorizationRulesTest.와일드카드_목적지는_멤버여도_거부한다` |
| 규칙에 없는 목적지 거부 | `WebSocketAuthorizationRulesTest.규칙에_없는_목적지는_거부한다` |
| 멤버 정상 통과 | `WebSocketAuthorizationRulesTest.멤버는_방을_구독한다` / `멤버는_방에_전송한다` |
| 거부 시 세션 유지 + 오류 채널 도착 | `RoomAuthorizationInterceptorTest.인가_실패는_프레임을_버리고_개인_큐로_사유를_보낸다` |
| 만료 토큰 프레임 거부 | `WsTokenExpiryTest.만료된_세션의_프레임은_거부한다` |
| 컨텍스트 로딩 | `SpringbootRealtimechatApplicationTests` |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 5: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓰고, 안 한 검증은 안 했다고 명시한다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** 인터셉터 순서(`JwtAuthChannelInterceptor` → `SecurityContextChannelInterceptor` → `RoomAuthorizationChannelInterceptor`)가 바뀌면 규칙 내용과 무관하게 전 연결이 거부되므로, 리뷰어가 반드시 봐야 하는 한 줄이다.

```bash
git push -u origin feat/ws-authorization
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 6: 배포 후 실측 항목을 PR에 남긴다**

이 PR은 단위 테스트로 규칙과 인터셉터를 고정하지만, **실제 소켓 위에서의 동작은 배포 후에만 확인할 수 있다.** PR 본문의 "구현 노트 / 알려진 한계"에 아래를 남긴다.

- 멤버가 방에 정상 입장·전송·타이핑되는지
- 비멤버 방을 구독 시도했을 때 소켓이 끊기지 않고 안내만 뜨는지
- 미인증 CONNECT가 거부되는지
- 재연결 후에도 정상 동작하는지

---

## Self-Review

**스펙 커버리지 (설계 §4·§5·§10):**

| 요구 | 태스크 |
|---|---|
| §5-5 의존성 + 기동 실패 방지 | Task 1 |
| §5-6 실측 3항목 | Task 1 (경로변수·시그니처), Task 1 Step 5 (컨텍스트 로딩) |
| §5-3 단일 진실 공급원 | Task 2 |
| §5-1 규칙표 + D3 와일드카드 거부 | Task 3 |
| §5-2 인터셉터 순서 | Task 4 (분리·배선), Task 5 (인가 추가) |
| §4 D1·D2 2단 거부 + 오류 채널 | Task 5 |
| §5-4 토큰 만료 | Task 6 |
| §10 거부 경로 테스트 | Task 3·5·6, Task 9 Step 4에서 대조 |
| PR 0 이월: 파싱 가드 | Task 7 |
| PR 0 이월: `destination` 활용 | Task 8 |

**설계에 있으나 이 계획에 없는 것:** §4 D4(탈퇴·강퇴 시 세션 종료). 프론트에 방 나가기 호출부가 없어 현재 트리거가 존재하지 않는다(백엔드 `DELETE /api/chatrooms/{id}/members`는 있으나 미사용). 강퇴 기능이 생기는 사이클에서 함께 구현한다. PR 본문의 "알려진 한계"에 명시한다.

**PR 0 이월 중 이 계획에 없는 것:** WS 테스트 하니스(실제 소켓 통합 테스트). 규칙과 인터셉터를 프레임 단위로 직접 검증해 §10 항목을 모두 덮으므로, 소켓을 띄우는 하니스는 비용 대비 이득이 낮다고 판단했다. 대신 Task 9 Step 6이 배포 후 실측 항목을 PR에 남긴다.

**타입 일관성:** `RoomAccess#isMember(Long, Long): boolean`(Task 2) → Task 3에서 호출. `AuthorizationManager<Message<?>>` 빈(Task 3) → Task 5 생성자. `WsErrorResponse(String code, String message, String destination)`(Task 5) → 프론트 `WsAuthzError { code, message, destination? }`와 필드명 일치. `JwtTokenProvider#getExpiresAt(String): Long`(Task 6) → 같은 태스크에서 소비. `JwtAuthChannelInterceptor`(Task 4) → Task 6에서 수정.
