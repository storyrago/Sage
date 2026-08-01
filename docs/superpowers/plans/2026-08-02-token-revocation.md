# 토큰 무효화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그아웃과 계정 삭제가 이미 발급된 토큰을 즉시 무효화하게 한다.

**Architecture:** Redis에 TTL 달린 거부목록을 두고(`jti` 단위 / 회원 단위), 토큰을 검증하는 두 지점(REST 필터, STOMP CONNECT)에서 판정한다. 회원 단위 키는 플래그가 아니라 무효화 시각이라, 그보다 나중에 발급된 재로그인 토큰은 통과한다. Redis 조회 실패는 통과시킨다(fail-open).

**Tech Stack:** Spring Boot 4.0.5, Spring Data Redis(`StringRedisTemplate`), jjwt 0.12.6, JUnit 5, H2, React + TypeScript + vitest

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-08-02-token-revocation-design.md`
- **스키마 변경 없음.** 마이그레이션 파일을 추가하지 않는다
- **새 의존성 없음. 새 이벤트 타입 없음.** `MemberDeletedEvent`를 재사용한다
- **새 `ErrorCode`를 만들지 않는다.** 무효화된 토큰은 기존 `UNAUTHORIZED`(401)로 응답한다
- **판정은 `TokenDenylist` 한 곳에서만 한다.** 필터·인터셉터가 Redis 키를 직접 읽지 않는다
- **검사는 두 지점 모두에 붙인다.** `JwtAuthenticationFilter`(REST)와 `JwtAuthChannelInterceptor`의 CONNECT 분기(WS). 한쪽만 하면 다른 쪽이 열린 채 남는다
- **키 삭제는 토큰 발급 경로 두 곳 모두에 붙인다.** `AuthService.login`과 `OAuth2SuccessHandler`
- **조회 실패는 fail-open**(통과 + 경고 로그). **로그아웃의 쓰기 실패는 fail-loud**(요청을 실패로 응답). **탈퇴의 쓰기 실패는 삼키고 경고**(삭제는 이미 커밋됐다)
- 키 형식: `jwt:denylist:jti:{jti}`, `jwt:denylist:member:{memberId}`. 값·TTL은 설계 §2 표 그대로
- 테스트는 실제 Redis를 쓴다(`LoginRateLimitTest`와 같은 방식). CI에 `redis:7` 서비스가 이미 있다(`.github/workflows/ci.yml:15`)
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm run lint && npm test && npm run build`
- 브랜치: develop에서 `feat/token-revocation`을 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사를 넣지 않는다

## File Structure

| 파일 | 변경 |
|---|---|
| `security/TokenDenylist.java` (신규) | 무효화 판단·등록·해제의 유일한 지점 |
| `security/JwtTokenProvider.java` (수정) | `jti` 발급, `getJti`·`getIssuedAt` 조회 |
| `security/JwtAuthenticationFilter.java` (수정) | 서명 검증 뒤 거부목록 확인 |
| `security/JwtAuthChannelInterceptor.java` (수정) | CONNECT에 동일 검사 |
| `config/SecurityConfig.java` (수정) | `POST /api/auth/logout`만 인증 필요로 뺀다 |
| `controller/AuthController.java` (수정) | `POST /api/auth/logout` |
| `service/AuthService.java` (수정) | `logout(token)`, 로그인 성공 시 회원 단위 키 삭제 |
| `security/OAuth2SuccessHandler.java` (수정) | 소셜 로그인도 같은 키 삭제 |
| `event/TokenRevocationListener.java` (신규) | `MemberDeletedEvent` → `revokeMember` |
| `frontend/src/lib/api.ts` (수정) | `logout(token)` |
| `frontend/src/App.tsx` (수정) | 세션 정리 전에 서버 로그아웃 호출 |

---

### Task 1: 거부목록과 토큰 식별자

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/security/TokenDenylist.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/security/JwtTokenProvider.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/TokenDenylistTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `TokenDenylist#isRevoked(String jti, Long memberId, Long issuedAtMillis): boolean`
  - `TokenDenylist#revokeToken(String jti, Long expiresAtMillis): void` — Redis 예외를 던진다
  - `TokenDenylist#revokeMember(Long memberId): void` — Redis 예외를 던진다
  - `TokenDenylist#clearMember(Long memberId): void` — 실패를 삼킨다
  - `JwtTokenProvider#getJti(String token): String` — 없거나 파싱 실패면 `null`
  - `JwtTokenProvider#getIssuedAt(String token): Long` — epoch millis, 파싱 실패면 `null`

**배경:** 지금 토큰에는 개별 식별자가 없어서 "이 토큰 하나만" 무효화할 수단이 없다. 판정 로직도 없다. 이 태스크가 그 둘을 만들고, Task 2~4가 그것을 붙인다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git checkout develop && git pull && git checkout -b feat/token-revocation
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/TokenDenylistTest.java`:

```java
package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Redis를 쓴다. TTL과 키 수명이 이 기능의 핵심이라 mock으로는 검증되지 않는다.
@SpringBootTest
class TokenDenylistTest {

    @Autowired TokenDenylist denylist;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired StringRedisTemplate redis;

    private static final Long MEMBER_ID = 987654L;
    private static final String JTI = "test-jti-0001";
    private static final String JTI_KEY = "jwt:denylist:jti:" + JTI;
    private static final String MEMBER_KEY = "jwt:denylist:member:" + MEMBER_ID;

    @AfterEach
    void cleanup() {
        redis.delete(JTI_KEY);
        redis.delete(MEMBER_KEY);
    }

    @Test
    void 아무것도_무효화하지_않으면_통과한다() {
        assertThat(denylist.isRevoked(JTI, MEMBER_ID, System.currentTimeMillis())).isFalse();
    }

    @Test
    void 무효화된_jti는_거부한다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() + 60_000L);

        assertThat(denylist.isRevoked(JTI, MEMBER_ID, System.currentTimeMillis())).isTrue();
    }

    @Test
    void 같은_회원의_다른_jti는_통과한다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() + 60_000L);

        assertThat(denylist.isRevoked("other-jti", MEMBER_ID, System.currentTimeMillis())).isFalse();
    }

    @Test
    void jti_키의_TTL이_토큰_만료까지로_설정된다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() + 60_000L);

        Long ttl = redis.getExpire(JTI_KEY);
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void 이미_만료된_토큰은_거부목록에_올리지_않는다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() - 1L);

        assertThat(redis.hasKey(JTI_KEY)).isFalse();
    }

    @Test
    void 회원_단위_무효화는_그_이전에_발급된_토큰을_거부한다() {
        long issuedAt = System.currentTimeMillis() - 5_000L;

        denylist.revokeMember(MEMBER_ID);

        assertThat(denylist.isRevoked(null, MEMBER_ID, issuedAt)).isTrue();
    }

    @Test
    void 회원_단위_무효화_이후에_발급된_토큰은_통과한다() {
        denylist.revokeMember(MEMBER_ID);
        long issuedAfter = System.currentTimeMillis() + 5_000L;

        assertThat(denylist.isRevoked(null, MEMBER_ID, issuedAfter)).isFalse();
    }

    @Test
    void 회원_단위_키를_지우면_옛_토큰도_다시_통과한다() {
        long issuedAt = System.currentTimeMillis() - 5_000L;
        denylist.revokeMember(MEMBER_ID);

        denylist.clearMember(MEMBER_ID);

        assertThat(denylist.isRevoked(null, MEMBER_ID, issuedAt)).isFalse();
    }

    @Test
    void 회원_단위_키의_TTL이_액세스_토큰_수명으로_설정된다() {
        denylist.revokeMember(MEMBER_ID);

        Long ttl = redis.getExpire(MEMBER_KEY);
        // 테스트 설정의 access-token-expiration-ms는 3600000
        assertThat(ttl).isBetween(3500L, 3600L);
    }

    @Test
    void 발급된_토큰에_jti가_실린다() {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");

        assertThat(jwtTokenProvider.getJti(token)).isNotBlank();
    }

    @Test
    void 같은_회원의_두_토큰은_서로_다른_jti를_가진다() {
        String first = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");
        String second = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");

        assertThat(jwtTokenProvider.getJti(first)).isNotEqualTo(jwtTokenProvider.getJti(second));
    }

    @Test
    void 발급_시각을_토큰에서_읽는다() {
        long before = System.currentTimeMillis() - 1_000L;
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");

        Long issuedAt = jwtTokenProvider.getIssuedAt(token);

        assertThat(issuedAt).isNotNull();
        assertThat(issuedAt).isGreaterThanOrEqualTo(before);
    }

    @Test
    void 잘못된_토큰의_jti와_발급_시각은_null() {
        assertThat(jwtTokenProvider.getJti("not-a-token")).isNull();
        assertThat(jwtTokenProvider.getIssuedAt("not-a-token")).isNull();
    }

    @Test
    void 발급_시각을_모르면_회원_단위_판정을_하지_않는다() {
        denylist.revokeMember(MEMBER_ID);

        assertThat(denylist.isRevoked(null, MEMBER_ID, null)).isFalse();
    }
}
```

> `회원_단위_키의_TTL` 검증에서 3600을 그대로 쓰는 이유는 `src/test/resources/application.yaml`의 `jwt.access-token-expiration-ms: 3600000`이기 때문이다. 그 값을 바꾸면 이 테스트도 함께 바꾼다.

- [ ] **Step 3: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*TokenDenylistTest*'
```

기대: 컴파일 실패 — `TokenDenylist`, `getJti`, `getIssuedAt`가 없다.

- [ ] **Step 4: `TokenDenylist` 작성**

`src/main/java/com/example/springboot_realtimechat/security/TokenDenylist.java` (신규):

```java
package com.example.springboot_realtimechat.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 발급된 토큰의 무효화 여부를 판단하고 등록한다. 판정은 여기 한 곳에서만 한다.
 * 키는 TTL로 스스로 사라지므로 정리 작업이 없다.
 */
@Slf4j
@Component
public class TokenDenylist {

    private static final String JTI_PREFIX = "jwt:denylist:jti:";
    private static final String MEMBER_PREFIX = "jwt:denylist:member:";

    private final StringRedisTemplate redis;
    private final long accessTokenExpirationMs;

    public TokenDenylist(
            StringRedisTemplate redis,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs
    ) {
        this.redis = redis;
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    /**
     * 이 토큰이 무효화됐는지. Redis 조회가 실패하면 통과시킨다(fail-open) —
     * 거부하면 Redis 장애가 전 사용자 로그인 불가로 번진다.
     */
    public boolean isRevoked(String jti, Long memberId, Long issuedAtMillis) {
        try {
            if (jti != null && Boolean.TRUE.equals(redis.hasKey(JTI_PREFIX + jti))) {
                return true;
            }
            if (memberId == null || issuedAtMillis == null) {
                return false;
            }
            String revokedAt = redis.opsForValue().get(MEMBER_PREFIX + memberId);
            return revokedAt != null && issuedAtMillis < Long.parseLong(revokedAt);
        } catch (Exception e) {
            log.warn("거부목록 조회 실패 — 토큰을 통과시킨다: memberId={}", memberId, e);
            return false;
        }
    }

    /** 토큰 하나를 무효화한다. TTL은 그 토큰의 남은 수명. */
    public void revokeToken(String jti, Long expiresAtMillis) {
        long remainingMs = expiresAtMillis - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return; // 이미 만료된 토큰은 서명 검증에서 걸린다
        }
        redis.opsForValue().set(JTI_PREFIX + jti, "1", Duration.ofMillis(remainingMs));
    }

    /**
     * 이 시각 이전에 발급된 그 회원의 토큰을 전부 무효화한다.
     * 플래그가 아니라 시각이라, 재로그인으로 받은 토큰은 통과한다.
     */
    public void revokeMember(Long memberId) {
        redis.opsForValue().set(
                MEMBER_PREFIX + memberId,
                String.valueOf(System.currentTimeMillis()),
                Duration.ofMillis(accessTokenExpirationMs));
    }

    /** 회원 단위 무효화를 해제한다. 재로그인이 같은 초에 일어나도 새 토큰이 막히지 않게 한다. */
    public void clearMember(Long memberId) {
        try {
            redis.delete(MEMBER_PREFIX + memberId);
        } catch (Exception e) {
            log.warn("회원 단위 무효화 해제 실패: memberId={}", memberId, e);
        }
    }
}
```

- [ ] **Step 5: 토큰에 `jti`를 싣고 조회 메서드를 추가한다**

`JwtTokenProvider.java`의 `createAccessToken`을 아래로 바꾼다.

```java
    //  로그인 성공 시 access token을 만듦.
    public String createAccessToken(Long memberId, String email) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — 토큰 하나를 지목해 무효화하는 식별자
                .subject(String.valueOf(memberId))
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getSigningKey())
                .compact();
    }
```

`getExpiresAt` 아래에 두 메서드를 추가한다.

```java
    /** 토큰의 식별자(jti). 없거나 파싱할 수 없으면 null. */
    public String getJti(String token) {
        try {
            return parseClaims(token).getId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 토큰의 발급 시각(epoch millis). 없거나 파싱할 수 없으면 null. */
    public Long getIssuedAt(String token) {
        try {
            Date issuedAt = parseClaims(token).getIssuedAt();
            return issuedAt != null ? issuedAt.getTime() : null;
        } catch (Exception e) {
            return null;
        }
    }
```

import를 추가한다.

```java
import java.util.UUID;
```

- [ ] **Step 6: 테스트 통과 확인**

Redis가 로컬에 떠 있어야 한다. 없으면 먼저 띄운다.

```bash
docker run -d --rm -p 6379:6379 --name chat-test-redis redis:7 || true
```

```bash
./gradlew test --tests '*TokenDenylistTest*'
```

기대: PASS — 14 tests

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/TokenDenylist.java src/main/java/com/example/springboot_realtimechat/security/JwtTokenProvider.java src/test/java/com/example/springboot_realtimechat/security/TokenDenylistTest.java
git commit -m "feat(auth): 토큰 거부목록과 토큰 식별자 추가"
```

---

### Task 2: 검사 지점 두 곳에 붙인다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/ws/WsTokenExpiryTest.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/RevokedTokenRejectionTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 `TokenDenylist`와 `JwtTokenProvider#getJti`·`#getIssuedAt`
- Produces: 무효화된 토큰이 REST에서는 인증 미설정 → 401, WS CONNECT에서는 사용자 미설정 → 이후 프레임이 인가에 걸림

**배경:** 거부목록이 있어도 아무도 읽지 않으면 아무것도 막히지 않는다. 토큰을 검증하는 지점은 REST 필터와 STOMP CONNECT 둘뿐이고, 한쪽만 막으면 다른 쪽이 열린 채 남는다.

`WsTokenExpiryTest`는 인터셉터를 `new JwtAuthChannelInterceptor(provider)`로 직접 만든다(`WsTokenExpiryTest.java:32`). 생성자가 바뀌므로 이 테스트도 함께 고친다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/RevokedTokenRejectionTest.java`:

```java
package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서명과 만료를 통과해도 무효화된 토큰은 인증되면 안 된다. REST와 WebSocket 양쪽에서 본다.
@SpringBootTest
@AutoConfigureMockMvc
class RevokedTokenRejectionTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TokenDenylist denylist;
    @Autowired JwtAuthChannelInterceptor interceptor;
    @Autowired StringRedisTemplate redis;

    private static final Long MEMBER_ID = 987655L;

    private final MessageChannel channel = mock(MessageChannel.class);

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
    }

    private StompHeaderAccessor connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> sent = interceptor.preSend(message, channel);
        return MessageHeaderAccessor.getAccessor(sent, StompHeaderAccessor.class);
    }

    @Test
    void 무효화되지_않은_토큰은_REST를_통과한다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 무효화된_토큰의_REST_요청은_401이다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        denylist.revokeToken(jwtTokenProvider.getJti(token), jwtTokenProvider.getExpiresAt(token));

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 같은_회원의_다른_토큰은_계속_유효하다() throws Exception {
        String revoked = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        String other = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        denylist.revokeToken(jwtTokenProvider.getJti(revoked), jwtTokenProvider.getExpiresAt(revoked));

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());
    }

    @Test
    void 회원_단위_무효화_이전_토큰의_REST_요청은_401이다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        Thread.sleep(1_100L);   // iat는 초 단위다. 무효화 시각이 발급 시각보다 확실히 뒤여야 한다
        denylist.revokeMember(MEMBER_ID);

        mockMvc.perform(get("/api/chatrooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 무효화되지_않은_토큰의_CONNECT는_사용자를_세운다() {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");

        assertThat(connect(token).getUser()).isNotNull();
    }

    @Test
    void 무효화된_토큰의_CONNECT는_사용자를_세우지_않는다() {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        denylist.revokeToken(jwtTokenProvider.getJti(token), jwtTokenProvider.getExpiresAt(token));

        assertThat(connect(token).getUser()).isNull();
    }

    @Test
    void 회원_단위_무효화_이전_토큰의_CONNECT는_사용자를_세우지_않는다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "revoke@e.com");
        Thread.sleep(1_100L);
        denylist.revokeMember(MEMBER_ID);

        assertThat(connect(token).getUser()).isNull();
    }
}
```

> `무효화되지_않은_토큰은_REST를_통과한다`가 `GET /api/chatrooms`를 200으로 기대하는 이유는 방 목록이 전체 공개 조회이기 때문이다. 200이 아니면 그 엔드포인트의 인가 정책이 바뀐 것이니, 인증만 확인하는 다른 엔드포인트로 바꾼다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*RevokedTokenRejectionTest*'
```

기대: 무효화 관련 5건 FAIL — 검사가 없으므로 무효화된 토큰도 200이고 CONNECT도 사용자를 세운다.

- [ ] **Step 3: REST 필터에 검사를 붙인다**

`JwtAuthenticationFilter.java`를 아래로 바꾼다.

```java
package com.example.springboot_realtimechat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;

    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    )throws ServletException, IOException{
        String authorizationHeader = request.getHeader("Authorization");
        if(authorizationHeader != null && authorizationHeader.startsWith("Bearer ")){
            String token = authorizationHeader.substring(7);
            if(jwtTokenProvider.validateToken(token)){
                Long memberId = jwtTokenProvider.getMemberId(token);
                String email = jwtTokenProvider.getEmail(token);

                // 서명과 만료를 통과해도 로그아웃·탈퇴로 무효화된 토큰은 인증하지 않는다.
                boolean revoked = tokenDenylist.isRevoked(
                        jwtTokenProvider.getJti(token),
                        memberId,
                        jwtTokenProvider.getIssuedAt(token));

                if (!revoked) {
                    CustomUserDetails customUserDetails = new CustomUserDetails(memberId, email);

                    UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
                            new UsernamePasswordAuthenticationToken(
                                    customUserDetails,
                                    null,
                                    customUserDetails.getAuthorities()
                            );
                    SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
                }
            }
        }

        //jwt 인증
        filterChain.doFilter(request,response);
    }

}
```

- [ ] **Step 4: WS CONNECT에 같은 검사를 붙인다**

`JwtAuthChannelInterceptor.java`에서 필드를 하나 추가한다.

```java
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;
```

CONNECT 분기의 `if (jwtTokenProvider.validateToken(token)) { ... }` 블록을 아래로 바꾼다.

```java
                if (jwtTokenProvider.validateToken(token)) {
                    Long memberId = jwtTokenProvider.getMemberId(token);
                    String email = jwtTokenProvider.getEmail(token);

                    // REST와 같은 판정이다. 한쪽만 막으면 다른 쪽이 열린 채 남는다.
                    boolean revoked = tokenDenylist.isRevoked(
                            jwtTokenProvider.getJti(token),
                            memberId,
                            jwtTokenProvider.getIssuedAt(token));

                    if (!revoked) {
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
```

- [ ] **Step 5: 기존 WS 테스트의 생성자 호출을 맞춘다**

`src/test/java/com/example/springboot_realtimechat/ws/WsTokenExpiryTest.java`의 `setUp`을 아래로 바꾼다.

```java
    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3600000L);
        // 이 테스트는 만료 처리만 본다. 거부목록 판정은 RevokedTokenRejectionTest가 본다.
        TokenDenylist denylist = mock(TokenDenylist.class);
        interceptor = new JwtAuthChannelInterceptor(provider, denylist);
    }
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.security.TokenDenylist;
```

`mock`은 이미 import되어 있다(`WsTokenExpiryTest.java:19`). mock의 `isRevoked`는 기본값 `false`를 돌려주므로 기존 기대가 그대로 유지된다.

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests '*RevokedTokenRejectionTest*' --tests '*WsTokenExpiryTest*'
```

기대: 둘 다 PASS

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/JwtAuthenticationFilter.java src/main/java/com/example/springboot_realtimechat/security/JwtAuthChannelInterceptor.java src/test/java/com/example/springboot_realtimechat/security/RevokedTokenRejectionTest.java src/test/java/com/example/springboot_realtimechat/ws/WsTokenExpiryTest.java
git commit -m "feat(auth): REST와 WebSocket 인증에서 거부목록을 확인"
```

---

### Task 3: 로그아웃 엔드포인트와 재로그인 통과

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/config/SecurityConfig.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/AuthController.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/AuthService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/LogoutTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 `TokenDenylist`, Task 2의 검사 지점
- Produces: `POST /api/auth/logout` → 204. `AuthService#logout(String token): void`

**배경:** 지금 로그아웃은 프론트가 `localStorage`를 지우는 것이 전부다. 서버에는 그런 개념이 없다.

**SecurityConfig를 반드시 함께 고친다.** `/api/auth/**`가 `permitAll`이라(`SecurityConfig.java:47`) 새 엔드포인트를 그냥 만들면 **미인증 요청이 컨트롤러까지 도달한다.** Spring Security의 `requestMatchers`는 먼저 등록된 규칙이 이긴다 — 로그아웃 규칙을 `permitAll` 블록 **앞에** 둔다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/LogoutTest.java`:

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.LoginRequest;
import com.example.springboot_realtimechat.dto.LoginResponse;
import com.example.springboot_realtimechat.service.AuthService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 로그아웃은 그 토큰만 죽인다. 다른 기기와 재로그인은 살아 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LogoutTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired AuthService authService;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TokenDenylist denylist;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
    }

    private LoginRequest req(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    @Test
    void 로그아웃은_204를_돌려준다() throws Exception {
        Member member = memberService.create("logout1@e.com", "1234", "로그아웃");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void 로그아웃한_토큰으로는_API를_호출할_수_없다() throws Exception {
        Member member = memberService.create("logout2@e.com", "1234", "로그아웃2");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃해도_같은_회원의_다른_기기_토큰은_유효하다() throws Exception {
        Member member = memberService.create("logout3@e.com", "1234", "로그아웃3");
        String phone = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        String laptop = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + phone))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + laptop))
                .andExpect(status().isOk());
    }

    @Test
    void 미인증_로그아웃은_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jti가_없는_토큰의_로그아웃은_그_회원_전체를_무효화한다() {
        Member member = memberService.create("logout4@e.com", "1234", "구토큰");
        String legacyToken = legacyTokenWithoutJti(member.getId(), member.getEmail());

        authService.logout(legacyToken);

        assertThat(denylist.isRevoked(null, member.getId(), System.currentTimeMillis() - 5_000L)).isTrue();
    }

    @Test
    void 회원_단위_무효화_이후_재로그인한_토큰은_통과한다() throws Exception {
        Member member = memberService.create("logout5@e.com", "1234", "재로그인");
        denylist.revokeMember(member.getId());

        LoginResponse response = authService.login(req("logout5@e.com", "1234"), "203.0.113.9");

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + response.getAccessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인_성공이_회원_단위_키를_지운다() {
        Member member = memberService.create("logout6@e.com", "1234", "키삭제");
        denylist.revokeMember(member.getId());

        authService.login(req("logout6@e.com", "1234"), "203.0.113.10");

        assertThat(redis.hasKey("jwt:denylist:member:" + member.getId())).isFalse();
    }

    // jti 도입 이전에 발급된 토큰을 흉내낸다. 배포 직후 최대 1시간 동안 실제로 존재한다.
    private String legacyTokenWithoutJti(Long memberId, String email) {
        long now = System.currentTimeMillis();
        return io.jsonwebtoken.Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("email", email)
                .issuedAt(new java.util.Date(now))
                .expiration(new java.util.Date(now + 3600000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-key-for-jwt-authentication-1234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }
}
```

> `LoginResponse`의 접근자 이름이 `getAccessToken()`이 아니면 `src/main/java/com/example/springboot_realtimechat/dto/LoginResponse.java`에서 확인해 맞춘다. 시크릿 문자열은 `src/test/resources/application.yaml`의 `jwt.secret`과 같아야 한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*LogoutTest*'
```

기대: 컴파일 실패(`authService.logout` 없음) 또는 엔드포인트 부재로 404.

- [ ] **Step 3: 로그아웃 경로만 인증 필요로 뺀다**

`SecurityConfig.java`의 `authorizeHttpRequests` 블록에서 `requestMatchers(...)` **바로 위**에 한 줄을 넣는다.

```java
                .authorizeHttpRequests(auth -> auth
                        // /api/auth/**의 permitAll보다 먼저 등록해야 한다. 뒤에 두면 미인증 요청이 컨트롤러까지 온다.
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(
                                "/api/auth/**",      // 로그인
```

import를 추가한다.

```java
import org.springframework.http.HttpMethod;
```

- [ ] **Step 4: `AuthService`에 로그아웃과 키 삭제를 넣는다**

`AuthService.java`에 필드를 추가한다.

```java
    private final LoginRateLimiter loginRateLimiter;
    private final TokenDenylist tokenDenylist;
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.security.TokenDenylist;
```

`login`에서 토큰을 만들기 직전에 한 줄을 넣는다.

```java
        loginRateLimiter.reset(clientIp);
        // 회원 단위 무효화를 해제한다. iat는 초 단위라, 지우지 않으면 같은 초의 재로그인이 막힌다.
        tokenDenylist.clearMember(member.getId());
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        return new LoginResponse(accessToken);
```

클래스 끝에 `logout`을 추가한다.

```java
    public void logout(String token) {
        String jti = jwtTokenProvider.getJti(token);
        Long expiresAt = jwtTokenProvider.getExpiresAt(token);
        if (jti != null && expiresAt != null) {
            tokenDenylist.revokeToken(jti, expiresAt);
            return;
        }
        // jti가 없는 토큰은 하나만 지목할 수 없다. 그 회원 전체를 무효화한다.
        tokenDenylist.revokeMember(jwtTokenProvider.getMemberId(token));
    }
```

Redis 쓰기가 실패하면 예외가 그대로 올라가 요청이 실패한다. 사용자가 "로그아웃됐다"고 오해하면 안 되므로 삼키지 않는다.

- [ ] **Step 5: 컨트롤러에 엔드포인트를 추가한다**

`AuthController.java`의 `login` 아래에 추가한다.

```java
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        // SecurityConfig가 이 경로를 인증 필수로 두므로, 여기 도달했다면 유효한 Bearer가 있다.
        authService.logout(request.getHeader("Authorization").substring(7));
    }
```

import를 추가한다.

```java
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
```

- [ ] **Step 6: 소셜 로그인에도 같은 키 삭제를 붙인다**

`OAuth2SuccessHandler.java`에 필드를 추가한다.

```java
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;
```

토큰 생성 직전에 한 줄을 넣는다.

```java
        // 이메일 로그인과 같은 이유로 회원 단위 무효화를 해제한다(AuthService.login).
        tokenDenylist.clearMember(member.getId());
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
```

`TokenDenylist`는 같은 패키지라 import가 필요 없다.

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests '*LogoutTest*'
```

기대: PASS — 7 tests

- [ ] **Step 8: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/config/SecurityConfig.java src/main/java/com/example/springboot_realtimechat/controller/AuthController.java src/main/java/com/example/springboot_realtimechat/service/AuthService.java src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java src/test/java/com/example/springboot_realtimechat/security/LogoutTest.java
git commit -m "feat(auth): 로그아웃 엔드포인트로 토큰을 무효화"
```

---

### Task 4: 계정 삭제가 토큰을 무효화한다

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/event/TokenRevocationListener.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/DeletedMemberTokenTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 `TokenDenylist#revokeMember`, 기존 `MemberDeletedEvent`
- Produces: 없음

**배경:** 계정을 지워도 그 토큰은 만료까지 인증을 통과한다. 회원 행이 사라진 상태로 요청이 각 엔드포인트의 인가까지 내려가, 인증이 답했어야 할 질문을 인가가 대신 답하고 있다.

`MemberDeletedEvent`는 이미 존재하고 구독 회수가 쓰고 있다(`SubscriptionRevocationListener.java:28`). 새 이벤트를 만들지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/DeletedMemberTokenTest.java`:

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 탈퇴는 AFTER_COMMIT 이벤트로 무효화한다. @Transactional을 붙이면 커밋되지 않아
// 리스너가 돌지 않는다 — 이 테스트는 커밋되는 테스트여야 한다.
@SpringBootTest
@AutoConfigureMockMvc
class DeletedMemberTokenTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JwtAuthChannelInterceptor interceptor;
    @Autowired StringRedisTemplate redis;

    @MockitoBean S3Service s3Service;

    private final MessageChannel channel = mock(MessageChannel.class);

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
        memberRepository.deleteAll();
    }

    private StompHeaderAccessor connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> sent = interceptor.preSend(message, channel);
        return MessageHeaderAccessor.getAccessor(sent, StompHeaderAccessor.class);
    }

    @Test
    void 탈퇴한_회원의_토큰으로는_API를_호출할_수_없다() throws Exception {
        Member member = memberService.create("deleted-token@e.com", "1234", "탈퇴자");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        Thread.sleep(1_100L);   // iat는 초 단위다. 무효화 시각이 발급 시각보다 확실히 뒤여야 한다

        memberService.delete(member.getId());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴한_회원의_토큰은_CONNECT도_통과하지_못한다() throws Exception {
        Member member = memberService.create("deleted-ws@e.com", "1234", "탈퇴자2");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        Thread.sleep(1_100L);

        memberService.delete(member.getId());

        assertThat(connect(token).getUser()).isNull();
    }

    @Test
    void 탈퇴는_그_회원의_토큰만_무효화한다() throws Exception {
        Member leaving = memberService.create("deleted-a@e.com", "1234", "탈퇴자3");
        Member staying = memberService.create("deleted-b@e.com", "1234", "남는이");
        String stayingToken = jwtTokenProvider.createAccessToken(staying.getId(), staying.getEmail());
        Thread.sleep(1_100L);

        memberService.delete(leaving.getId());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + stayingToken))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*DeletedMemberTokenTest*'
```

기대: 앞의 두 건 FAIL — 탈퇴해도 토큰이 그대로 통과한다.

- [ ] **Step 3: 리스너 작성**

`src/main/java/com/example/springboot_realtimechat/event/TokenRevocationListener.java` (신규):

```java
package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.security.TokenDenylist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationListener {

    private final TokenDenylist tokenDenylist;

    // 커밋된 뒤에만 무효화한다. 롤백된 탈퇴로 토큰을 죽이면 멀쩡한 사용자가 로그아웃된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberDeleted(MemberDeletedEvent event) {
        try {
            tokenDenylist.revokeMember(event.memberId());
        } catch (Exception e) {
            // 삭제는 이미 커밋됐고 되돌릴 수 없다. 무효화 실패를 던져도 되살릴 것이 없다.
            log.warn("탈퇴 회원 토큰 무효화 실패: memberId={}", event.memberId(), e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests '*DeletedMemberTokenTest*'
```

기대: PASS — 3 tests

- [ ] **Step 5: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/event/TokenRevocationListener.java src/test/java/com/example/springboot_realtimechat/security/DeletedMemberTokenTest.java
git commit -m "feat(auth): 탈퇴 시 그 회원의 토큰을 무효화"
```

---

### Task 5: 프론트가 서버 로그아웃을 호출한다

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/lib/api.test.ts`

**Interfaces:**
- Consumes: `POST /api/auth/logout` → 204 (Task 3)
- Produces: `logout(token: string): Promise<void>`

**배경:** 지금 로그아웃은 `clearSession()`이 전부다(`App.tsx:501`). 공용 PC에서 로그아웃해도 그 토큰은 만료까지 유효하다.

이 호출은 **전역 401 처리기를 타면 안 된다.** `request()`는 401에서 `unauthorizedHandler`를 부르고(`api.ts:82`), 그 처리기가 "세션이 만료되었어요" 안내를 띄운다 — 일부러 로그아웃하는 사용자에게 나올 문구가 아니다. 그래서 `logout`은 `fetch`를 직접 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/lib/api.test.ts` 상단의 import를 바꾸고 파일 끝에 describe 블록을 추가한다.

```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { toMessage, BackendMessage, logout, setUnauthorizedHandler } from './api';
```

```ts
describe('logout', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(null);
  });

  it('토큰을 Authorization 헤더로 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await logout('tok-123');

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/logout');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-123');
  });

  it('실패하면 예외를 던지되 전역 401 처리기는 부르지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);

    await expect(logout('tok-123')).rejects.toThrow();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend/frontend && npm test
```

기대: FAIL — `logout`이 export되지 않았다.

- [ ] **Step 3: `logout`을 추가한다**

`frontend/src/lib/api.ts`에서 `getMe` 바로 위에 추가한다.

```ts
/**
 * 서버가 이 토큰을 즉시 무효화한다. 세션을 지우기 전에 부른다.
 * 전역 401 처리기가 "세션이 만료되었어요"를 띄우면 안 되므로 request()를 쓰지 않는다.
 */
export async function logout(token: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/auth/logout`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    throw new ApiError('로그아웃에 실패했습니다.', response.status);
  }
}
```

- [ ] **Step 4: 앱이 세션을 지우기 전에 호출하게 한다**

`frontend/src/App.tsx`의 import 목록에 `logout`을 추가한다(알파벳 순서를 지킨다 — `joinChatRoom` 다음).

```ts
  joinChatRoom,
  logout,
  markRoomRead,
```

`handleLogout`을 아래로 바꾼다.

```tsx
  const handleLogout = async () => {
    // 서버 무효화가 실패해도 이 기기의 세션은 정리한다. 남겨두면 사용자가 갇힌다.
    let serverLogoutFailed = false;
    if (token) {
      try {
        await logout(token);
      } catch {
        serverLogoutFailed = true;
      }
    }
    clearSession();
    if (serverLogoutFailed) {
      setNotice('로그아웃 요청이 서버에 닿지 않았어요. 이 기기에서만 로그아웃됩니다.');
    }
  };
```

`clearSession()`이 마지막에 `setNotice(null)`을 하므로, 안내는 그 뒤에 세운다.

- [ ] **Step 5: 검증**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend/frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 6: 커밋**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git add frontend/src/lib/api.ts frontend/src/lib/api.test.ts frontend/src/App.tsx
git commit -m "feat(frontend): 로그아웃 시 서버에 토큰 무효화를 요청"
```

---

### Task 6: 최종 검증과 PR

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

- [ ] **Step 4: 거부목록 판정이 한 곳에만 있는지 확인**

```bash
grep -rn "jwt:denylist" src/main/java
```

기대: `TokenDenylist.java`의 두 상수만 나온다. 다른 파일이 키 문자열을 직접 들고 있으면 Task 1로 돌아간다.

- [ ] **Step 5: 검사 지점이 둘 다 붙었는지 확인**

```bash
grep -rn "isRevoked" src/main/java
```

기대: `TokenDenylist`(선언), `JwtAuthenticationFilter`, `JwtAuthChannelInterceptor` 세 곳.

- [ ] **Step 6: 키 삭제가 발급 경로 둘 다에 붙었는지 확인**

```bash
grep -rn "clearMember" src/main/java
```

기대: `TokenDenylist`(선언), `AuthService`, `OAuth2SuccessHandler` 세 곳.

- [ ] **Step 7: 설계 §7 자동 테스트 목록과 대조**

| §7 항목 | 테스트 |
|---|---|
| 로그아웃한 토큰으로 API 호출 시 401 | `LogoutTest.로그아웃한_토큰으로는_API를_호출할_수_없다` |
| 같은 회원의 다른 기기 토큰은 유효 | `LogoutTest.로그아웃해도_같은_회원의_다른_기기_토큰은_유효하다` |
| `jti` 없는 토큰의 로그아웃 → 회원 전체 무효화 | `LogoutTest.jti가_없는_토큰의_로그아웃은_그_회원_전체를_무효화한다` |
| 회원 단위 무효화 이후 재로그인 토큰은 통과 | `LogoutTest.회원_단위_무효화_이후_재로그인한_토큰은_통과한다` |
| 계정 삭제 후 API 호출 401 | `DeletedMemberTokenTest.탈퇴한_회원의_토큰으로는_API를_호출할_수_없다` |
| 계정 삭제 후 WS CONNECT 거부 | `DeletedMemberTokenTest.탈퇴한_회원의_토큰은_CONNECT도_통과하지_못한다` |
| 거부목록 TTL이 토큰 만료에 맞춰 설정 | `TokenDenylistTest.jti_키의_TTL이_토큰_만료까지로_설정된다` / `회원_단위_키의_TTL이_액세스_토큰_수명으로_설정된다` |
| Redis 조회 실패 시 통과 + 경고 | `TokenDenylist.isRevoked`의 catch 블록 — Step 8에서 코드로 확인 |
| 로그인 성공이 회원 단위 키를 지운다 | `LogoutTest.로그인_성공이_회원_단위_키를_지운다` |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 8: fail-open이 실제로 구현됐는지 코드로 확인**

```bash
grep -n -A 3 "catch (Exception e)" src/main/java/com/example/springboot_realtimechat/security/TokenDenylist.java
```

기대: `isRevoked`의 catch가 `log.warn` 후 `return false`. `revokeToken`·`revokeMember`에는 catch가 없다(로그아웃 실패는 요청 실패로 드러나야 한다).

- [ ] **Step 9: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓴다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** `SecurityConfig`의 `POST /api/auth/logout` 규칙은 `/api/auth/**` permitAll보다 **앞에** 있어야 한다. 순서가 바뀌면 로그아웃이 미인증 요청을 그대로 받아 컨트롤러에서 터진다.

```bash
git push -u origin feat/token-revocation
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 10: 배포 후 실측 항목을 PR에 남긴다**

설계 §7의 배포 후 실측 항목 그대로다.

- 로그아웃한 뒤 옛 토큰으로 API를 호출하면 401인지(개발자 도구에서 토큰을 복사해 확인)
- 로그아웃해도 다른 기기의 세션이 유지되는지
- 탈퇴 직후 그 토큰으로 접속이 막히는지
- 로그아웃 → 재로그인이 곧바로 되는지
- 소셜 로그인으로 재로그인해도 곧바로 되는지
- 배포 후 1시간 동안 애플리케이션 로그에 "거부목록 조회 실패"가 없는지

---

## Self-Review

**스펙 커버리지 (설계 §2·§3·§5·§7):**

| 요구 | 태스크 |
|---|---|
| D1 Redis 거부목록, TTL, 회원 단위 키는 시각 | Task 1 |
| D2 `jti` 발급, 없는 토큰은 회원 단위로 대체 | Task 1(발급), Task 3(대체) |
| D3 로그인 성공 시 회원 단위 키 삭제 — 두 경로 | Task 3 |
| D4 판정은 `TokenDenylist` 한 곳 | Task 1, Task 6 Step 4에서 확인 |
| D5 검사는 REST 필터와 WS CONNECT | Task 2, Task 6 Step 5에서 확인 |
| D6 `POST /api/auth/logout` → 204 | Task 3 |
| D7 조회 실패는 fail-open | Task 1, Task 6 Step 8에서 확인 |
| §3 `TokenRevocationListener` | Task 4 |
| §3 프론트 로그아웃 호출 | Task 5 |
| §5 로그아웃 쓰기 실패는 요청 실패 | Task 3 Step 4(예외를 삼키지 않음) |
| §5 탈퇴 쓰기 실패는 삼키고 경고 | Task 4 Step 3 |
| §5 인증 실패는 기존 `UNAUTHORIZED` | Task 2(인증을 세우지 않아 기존 경로로 401) |
| §7 자동 테스트 | Task 1~5, Task 6 Step 7에서 대조 |
| §7 프론트 검증 | Task 5 Step 5, Task 6 Step 2 |

**설계에 없어 이 계획에서 채운 것:**

- `SecurityConfig`에서 `POST /api/auth/logout`을 `permitAll` 앞으로 빼는 것(Task 3 Step 3). 설계 §D6은 "인증된 요청"을 전제하는데, `/api/auth/**`가 permitAll이라 그 전제가 자동으로 성립하지 않는다
- `WsTokenExpiryTest`의 생성자 호출 수정(Task 2 Step 5). 인터셉터를 직접 `new`로 만드는 유일한 테스트다
- 프론트에서 서버 로그아웃이 실패했을 때의 동작(Task 5 Step 4). 로컬 세션은 정리하고 안내를 띄운다 — 남겨두면 사용자가 로그아웃하지 못한다
