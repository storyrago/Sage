# 소셜 로그인 일회용 코드 교환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 소셜 로그인 리다이렉트가 JWT 대신 일회용 코드를 싣게 해서, 302의 `Location` 헤더를 통해 토큰이 로그에 남지 않게 한다.

**Architecture:** `OAuth2SuccessHandler`가 토큰 대신 60초 TTL의 일회용 코드를 Redis에 저장하고 그 코드만 리다이렉트에 싣는다. 프론트가 그 코드를 `POST /api/auth/oauth/token`의 **본문**으로 보내 토큰을 받는다. 코드 소비는 `GETDEL` 한 명령이라 두 번 쓸 수 없다. 토큰 발급이 핸들러에서 교환 지점으로 옮겨가므로, 거기 딸린 `clearMember`도 함께 옮긴다.

**Tech Stack:** Spring Boot 4.0.5, Spring Data Redis(`StringRedisTemplate`), Spring Security OAuth2 Client, JUnit 5, H2, React + TypeScript + vitest

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-08-02-oauth-code-exchange-design.md`
- **스키마 변경 없음.** 마이그레이션 파일을 추가하지 않는다
- **새 의존성 없음. 새 `ErrorCode`를 만들지 않는다** — 교환 실패는 기존 `UNAUTHORIZED`(401)
- **`SecurityConfig`를 건드리지 않는다.** `/api/auth/**`가 이미 `permitAll`이라 교환 엔드포인트가 거기 걸린다. 로그아웃(`POST /api/auth/logout`)이 permitAll 앞에 따로 등록된 것과 반대 상황이다
- **코드 소비는 원자적이어야 한다.** `getAndDelete`(Redis `GETDEL`) 한 명령. 조회 후 삭제하는 두 단계로 만들지 않는다
- **`#token=` 경로를 남기지 않는다.** 프론트·백엔드가 같은 배포로 나가므로 과도기가 없다
- **토큰 발급 경로는 회원 단위 무효화 키를 지운다**(`2026-08-02-token-revocation-design.md` D3). 발급이 옮겨가면 `clearMember`도 옮긴다
- 키 형식: `oauth:code:{code}` → `memberId`, TTL 60초
- 오류 처리 방향: 코드 저장 실패(Redis) → `#oauth_error=`로 리다이렉트 / 코드 조회 실패(Redis) → 예외를 올려 500 / 코드 없음·만료·재사용 → 전부 401(구분하지 않는다)
- 테스트는 실제 Redis를 쓴다(`TokenDenylistTest`와 같은 방식). CI에 `redis:7` 서비스가 이미 있다
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm run lint && npm test && npm run build`
- 브랜치: develop에서 `feat/oauth-code-exchange`를 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사·회고를 넣지 않는다. 한국어 주석·테스트 메서드명이 이 레포의 관례다

## File Structure

| 파일 | 변경 |
|---|---|
| `security/OAuthCodeStore.java` (신규) | 코드 발급·소비의 유일한 지점 |
| `security/OAuth2SuccessHandler.java` (수정) | 코드를 싣는다. 토큰 발급·`clearMember`가 빠진다 |
| `dto/OAuthCodeRequest.java` (신규) | 교환 요청 본문 |
| `service/AuthService.java` (수정) | `exchangeOAuthCode(code)` |
| `controller/AuthController.java` (수정) | `POST /api/auth/oauth/token` |
| `frontend/src/lib/api.ts` (수정) | `exchangeOAuthCode(code)` |
| `frontend/src/App.tsx` (수정) | `#code=`를 읽어 교환한다 |
| `src/test/java/.../security/OAuth2SuccessHandlerTest.java` (수정) | 코드를 싣는지 검증으로 바뀐다 |

---

### Task 1: 코드 저장소

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/security/OAuthCodeStore.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/OAuthCodeStoreTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `OAuthCodeStore#issue(Long memberId): String` — 코드를 만들어 저장하고 돌려준다. Redis 예외를 그대로 올린다
  - `OAuthCodeStore#consume(String code): Long` — 코드를 소비하고 회원 id를 돌려준다. 없거나 이미 쓴 코드면 `null`. Redis 예외를 그대로 올린다

**배경:** 지금은 토큰을 그대로 URL에 싣는다. 그 자리를 대신할 값과, 그 값을 한 번만 쓰게 만드는 장치가 먼저 필요하다. Task 2가 이것을 배선한다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git checkout develop && git pull && git checkout -b feat/oauth-code-exchange
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/OAuthCodeStoreTest.java`:

```java
package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Redis를 쓴다. TTL과 1회용 소비가 이 기능의 핵심이라 mock으로는 검증되지 않는다.
@SpringBootTest
class OAuthCodeStoreTest {

    @Autowired OAuthCodeStore store;
    @Autowired StringRedisTemplate redis;

    private static final Long MEMBER_ID = 424242L;

    private String issuedCode;

    @AfterEach
    void cleanup() {
        if (issuedCode != null) {
            redis.delete("oauth:code:" + issuedCode);
            issuedCode = null;
        }
    }

    @Test
    void 발급한_코드로_회원_id를_얻는다() {
        issuedCode = store.issue(MEMBER_ID);

        assertThat(store.consume(issuedCode)).isEqualTo(MEMBER_ID);
    }

    @Test
    void 같은_코드는_두_번_쓸_수_없다() {
        issuedCode = store.issue(MEMBER_ID);

        assertThat(store.consume(issuedCode)).isEqualTo(MEMBER_ID);
        assertThat(store.consume(issuedCode)).isNull();
    }

    @Test
    void 소비하면_키가_남지_않는다() {
        issuedCode = store.issue(MEMBER_ID);

        store.consume(issuedCode);

        assertThat(redis.hasKey("oauth:code:" + issuedCode)).isFalse();
    }

    @Test
    void 존재하지_않는_코드는_null이다() {
        assertThat(store.consume("no-such-code")).isNull();
    }

    @Test
    void 비어_있는_코드는_null이다() {
        assertThat(store.consume(null)).isNull();
        assertThat(store.consume("")).isNull();
        assertThat(store.consume("   ")).isNull();
    }

    @Test
    void 코드의_TTL은_60초다() {
        issuedCode = store.issue(MEMBER_ID);

        Long ttl = redis.getExpire("oauth:code:" + issuedCode);
        assertThat(ttl).isBetween(50L, 60L);
    }

    @Test
    void 두_번_발급하면_서로_다른_코드다() {
        String first = store.issue(MEMBER_ID);
        String second = store.issue(MEMBER_ID);
        redis.delete("oauth:code:" + first);
        redis.delete("oauth:code:" + second);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 코드는_추측할_수_없을_만큼_길다() {
        issuedCode = store.issue(MEMBER_ID);

        assertThat(issuedCode).hasSizeGreaterThanOrEqualTo(32);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것 확인**

로컬 Redis가 떠 있어야 한다.

```bash
./gradlew test --tests '*OAuthCodeStoreTest*'
```

기대: 컴파일 실패 — `OAuthCodeStore`가 없다.

- [ ] **Step 4: `OAuthCodeStore` 작성**

`src/main/java/com/example/springboot_realtimechat/security/OAuthCodeStore.java` (신규):

```java
package com.example.springboot_realtimechat.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 소셜 로그인 리다이렉트가 실어 나르는 일회용 코드. 토큰을 URL에 싣지 않기 위한 것이다.
 * 코드는 짧게 살고 한 번만 쓸 수 있으므로, 로그에 남아도 의미가 없다.
 */
@Component
@RequiredArgsConstructor
public class OAuthCodeStore {

    private static final String PREFIX = "oauth:code:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    /** 코드를 만들어 회원 id와 함께 저장한다. 저장에 실패하면 로그인이 성립하지 않으므로 예외를 삼키지 않는다. */
    public String issue(Long memberId) {
        String code = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + code, String.valueOf(memberId), TTL);
        return code;
    }

    /**
     * 코드를 소비하고 회원 id를 돌려준다. 없거나 이미 쓴 코드면 null.
     * 조회와 삭제가 한 명령(GETDEL)이라, 같은 코드로 동시에 들어온 교환이 둘 다 성공할 수 없다.
     */
    public Long consume(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String memberId = redis.opsForValue().getAndDelete(PREFIX + code);
        return memberId != null ? Long.valueOf(memberId) : null;
    }
}
```

> `ValueOperations.getAndDelete`가 이 버전에 없으면 **직접 만들지 말고 보고해라.** 원자성이 이 설계의 핵심이라, 조회 후 삭제하는 두 단계로 대체하면 안 된다.

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*OAuthCodeStoreTest*'
```

기대: PASS — 8 tests

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/OAuthCodeStore.java src/test/java/com/example/springboot_realtimechat/security/OAuthCodeStoreTest.java
git commit -m "feat(auth): 소셜 로그인 일회용 코드 저장소 추가"
```

---

### Task 2: 리다이렉트와 교환 엔드포인트

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/OAuthCodeRequest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/AuthService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/AuthController.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandlerTest.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/OAuthCodeExchangeTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 `OAuthCodeStore#issue`·`#consume`
- Produces: `POST /api/auth/oauth/token` → `LoginResponse`. `AuthService#exchangeOAuthCode(String code): LoginResponse`

**배경:** 토큰 발급이 `OAuth2SuccessHandler`에서 교환 지점으로 옮겨간다. **그 이동에 `clearMember`가 딸려 있다** — 토큰 무효화 설계 D3이 "토큰을 발급하는 모든 경로가 회원 단위 무효화 키를 지운다"고 정했고, 지금 그것을 하는 곳이 이 핸들러다(`OAuth2SuccessHandler.java:38`). 핸들러에 남겨두면 리다이렉트 시점에 키를 지우고 교환 시점에 토큰을 만들게 되어, 그 사이에 무효화가 걸리면 방금 발급한 토큰이 거부된다.

이 태스크는 한 커밋으로 간다. 쪼개면 `clearMember`가 어디에도 없는 중간 상태가 생긴다.

- [ ] **Step 1: 실패하는 테스트 작성 (교환)**

`src/test/java/com/example/springboot_realtimechat/security/OAuthCodeExchangeTest.java`:

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 코드는 한 번만 쓸 수 있어야 하고, 교환은 인증 없이 되어야 하며, 발급 경로이므로
// 회원 단위 무효화 키를 지워야 한다(토큰 무효화 설계 D3).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OAuthCodeExchangeTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired OAuthCodeStore codeStore;
    @Autowired TokenDenylist denylist;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.keys("oauth:code:*").forEach(redis::delete);
        redis.keys("jwt:denylist:*").forEach(redis::delete);
    }

    private String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    void 발급된_코드를_교환하면_액세스_토큰이_나온다() throws Exception {
        Member member = memberService.create("exch1@e.com", "1234", "교환1");
        String code = codeStore.issue(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void 교환은_인증_없이_호출할_수_있다() throws Exception {
        Member member = memberService.create("exch2@e.com", "1234", "교환2");
        String code = codeStore.issue(member.getId());

        // Authorization 헤더가 없어도 401이 아니다
        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());
    }

    @Test
    void 같은_코드를_두_번_교환하면_두_번째는_401이다() throws Exception {
        Member member = memberService.create("exch3@e.com", "1234", "교환3");
        String code = codeStore.issue(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 존재하지_않는_코드는_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("no-such-code")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 코드가_가리키는_회원이_없으면_401이다() throws Exception {
        String code = codeStore.issue(99999999L);

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 교환이_회원_단위_무효화_키를_지운다() throws Exception {
        Member member = memberService.create("exch4@e.com", "1234", "교환4");
        denylist.revokeMember(member.getId());
        String code = codeStore.issue(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());

        assertThat(redis.hasKey("jwt:denylist:member:" + member.getId())).isFalse();
    }

    @Test
    void 교환으로_받은_토큰으로_API를_호출할_수_있다() throws Exception {
        Member member = memberService.create("exch5@e.com", "1234", "교환5");
        String code = codeStore.issue(member.getId());

        String response = mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*OAuthCodeExchangeTest*'
```

기대: 전부 FAIL — 엔드포인트가 없어 404다.

- [ ] **Step 3: 요청 DTO 추가**

`src/main/java/com/example/springboot_realtimechat/dto/OAuthCodeRequest.java` (신규):

```java
package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuthCodeRequest {
    @NotBlank
    String code;
}
```

- [ ] **Step 4: `AuthService`에 교환을 넣는다**

`AuthService.java`에 필드를 추가한다.

```java
    private final TokenDenylist tokenDenylist;
    private final OAuthCodeStore oAuthCodeStore;
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.security.OAuthCodeStore;
```

`logout` 아래에 추가한다.

```java
    /** 소셜 로그인 리다이렉트가 실어 온 일회용 코드를 액세스 토큰으로 바꾼다. */
    public LoginResponse exchangeOAuthCode(String code) {
        // 만료·재사용·위조를 구분하지 않는다. 구분하면 그 코드가 존재했는지를 알려주는 것이다.
        Long memberId = oAuthCodeStore.consume(code);
        if (memberId == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));
        // 토큰을 발급하는 경로는 회원 단위 무효화를 해제한다(AuthService.login과 같은 이유).
        tokenDenylist.clearMember(member.getId());
        return new LoginResponse(jwtTokenProvider.createAccessToken(member.getId(), member.getEmail()));
    }
```

- [ ] **Step 5: 컨트롤러에 엔드포인트를 추가한다**

`AuthController.java`의 `logout` 아래에 추가한다.

```java
    @PostMapping("/oauth/token")
    public LoginResponse exchangeOAuthCode(@Valid @RequestBody OAuthCodeRequest request) {
        return authService.exchangeOAuthCode(request.getCode());
    }
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.dto.OAuthCodeRequest;
```

**`SecurityConfig`를 건드리지 마라.** `/api/auth/**`가 이미 permitAll이라 이 경로가 거기 걸린다.

- [ ] **Step 6: 핸들러가 토큰 대신 코드를 싣게 한다**

`OAuth2SuccessHandler.java`를 아래로 바꾼다. `JwtTokenProvider`와 `TokenDenylist` 의존이 빠진다.

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {
    private final MemberRepository memberRepository;
    private final OAuthCodeStore oAuthCodeStore;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
        String provider = authToken.getAuthorizedClientRegistrationId().toUpperCase();
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        var member = memberRepository.findByProviderAndProviderId(provider, oidcUser.getSubject())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 토큰을 URL에 싣지 않는다. 리다이렉트를 만드는 302의 Location 헤더는 로그에 남는다.
        String code;
        try {
            code = oAuthCodeStore.issue(member.getId());
        } catch (Exception e) {
            // 코드를 저장하지 못하면 로그인이 성립하지 않는다. 실패 경로와 같은 형태로 안내한다.
            log.error("소셜 로그인 코드 발급 실패: memberId={}", member.getId(), e);
            response.sendRedirect(frontendUrl + "/#oauth_error=oauth_failed");
            return;
        }
        response.sendRedirect(frontendUrl + "/#code=" + code);
    }
}
```

- [ ] **Step 7: 기존 핸들러 테스트를 새 책임에 맞춘다**

`src/test/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandlerTest.java`를 아래로 바꾼다. `clearMember` 순서 검증은 교환 쪽으로 옮겨갔으므로(`OAuthCodeExchangeTest.교환이_회원_단위_무효화_키를_지운다`) 여기서는 **코드를 싣는지, 토큰이 새지 않는지**를 본다.

```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 리다이렉트에 자격증명을 싣지 않는다. 302의 Location 헤더는 로그에 남는다.
@ExtendWith(MockitoExtension.class)
class OAuth2SuccessHandlerTest {

    @Mock MemberRepository memberRepository;
    @Mock OAuthCodeStore oAuthCodeStore;
    @InjectMocks OAuth2SuccessHandler handler;

    private OAuth2AuthenticationToken authToken;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "frontendUrl", "https://app.example.com");

        Member member = mock(Member.class);
        given(member.getId()).willReturn(42L);

        OidcUser oidcUser = mock(OidcUser.class);
        authToken = mock(OAuth2AuthenticationToken.class);
        given(authToken.getAuthorizedClientRegistrationId()).willReturn("google");
        given(authToken.getPrincipal()).willReturn(oidcUser);
        given(oidcUser.getSubject()).willReturn("google-subject-1");
        given(memberRepository.findByProviderAndProviderId("GOOGLE", "google-subject-1"))
                .willReturn(Optional.of(member));

        response = mock(HttpServletResponse.class);
    }

    @Test
    void 리다이렉트에_토큰이_아니라_코드를_싣는다() throws Exception {
        given(oAuthCodeStore.issue(42L)).willReturn("one-time-code");

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        verify(response).sendRedirect("https://app.example.com/#code=one-time-code");
    }

    @Test
    void 리다이렉트_URL에_JWT가_들어가지_않는다() throws Exception {
        given(oAuthCodeStore.issue(42L)).willReturn("one-time-code");

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        // JWT는 점으로 구분된 3부분이다. 리다이렉트에 그런 형태가 있으면 안 된다.
        assertThat(captor.getValue()).doesNotContain("token=");
        assertThat(captor.getValue()).doesNotMatch(".*[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}.*");
    }

    @Test
    void 코드_발급에_실패하면_오류로_리다이렉트한다() throws Exception {
        willThrow(new RuntimeException("redis down")).given(oAuthCodeStore).issue(42L);

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        verify(response).sendRedirect("https://app.example.com/#oauth_error=oauth_failed");
    }
}
```

> Mockito strict stubbing 때문에 `@BeforeEach`의 stub이 쓰이지 않는 테스트가 있으면 실패한다. 그럴 때는 공통 stub을 각 테스트로 내리거나 `lenient()`를 쓰되, **단언을 약화시키지 마라.**

- [ ] **Step 8: 테스트 통과 확인**

```bash
./gradlew test --tests '*OAuthCodeExchangeTest*' --tests '*OAuth2SuccessHandlerTest*'
```

기대: 둘 다 PASS

- [ ] **Step 9: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. `OAuth2SuccessHandler`에서 `JwtTokenProvider`·`TokenDenylist` 의존이 빠졌으므로 그것을 참조하던 다른 테스트가 있으면 함께 맞춘다 — **단언을 약화시키지 마라.**

- [ ] **Step 10: 발급 경로가 둘인지 확인**

```bash
grep -rn "createAccessToken" src/main/java
```

기대: `JwtTokenProvider`(선언), `AuthService.login`, `AuthService.exchangeOAuthCode` 세 곳. `OAuth2SuccessHandler`에 남아 있으면 Step 6으로 돌아간다.

```bash
grep -rn "clearMember" src/main/java
```

기대: `TokenDenylist`(선언), `AuthService.login`, `AuthService.exchangeOAuthCode` 세 곳.

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java src/main/java/com/example/springboot_realtimechat/dto/OAuthCodeRequest.java src/main/java/com/example/springboot_realtimechat/service/AuthService.java src/main/java/com/example/springboot_realtimechat/controller/AuthController.java src/test/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandlerTest.java src/test/java/com/example/springboot_realtimechat/security/OAuthCodeExchangeTest.java
git commit -m "feat(auth): 소셜 로그인 토큰을 일회용 코드 교환으로 전달"
```

---

### Task 3: 프론트가 코드를 교환한다

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/lib/api.test.ts`

**Interfaces:**
- Consumes: `POST /api/auth/oauth/token` → `{ accessToken, tokenType }` (Task 2)
- Produces: `exchangeOAuthCode(code: string): Promise<string>`

**배경:** 지금 `App.tsx`가 `#token=`을 읽어 그대로 쓴다(`App.tsx:157`). 백엔드가 `#code=`를 싣게 되므로 프론트가 교환 한 단계를 앞에 넣어야 한다.

이 호출은 **전역 401 처리기를 타면 안 된다.** `request()`는 401에서 `unauthorizedHandler`를 부르고 그 처리기가 "세션이 만료되었어요"를 띄운다 — 아직 로그인하는 중인 사용자에게 나올 문구가 아니다. `logout()`과 같은 이유로 `fetch`를 직접 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/lib/api.test.ts`의 import에 `exchangeOAuthCode`를 추가하고, 파일 끝에 describe 블록을 추가한다.

```ts
describe('exchangeOAuthCode', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(null);
  });

  it('코드를 본문으로 보내고 액세스 토큰을 돌려준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: 'tok-abc', tokenType: 'Bearer' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(exchangeOAuthCode('code-123')).resolves.toBe('tok-abc');

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/oauth/token');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ code: 'code-123' });
  });

  it('코드를 URL에 싣지 않는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: 'tok-abc' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await exchangeOAuthCode('code-123');

    expect(String(fetchMock.mock.calls[0][0])).not.toContain('code-123');
  });

  it('실패하면 예외를 던지되 전역 401 처리기는 부르지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);

    await expect(exchangeOAuthCode('code-123')).rejects.toThrow();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend/frontend && npm test
```

기대: FAIL — `exchangeOAuthCode`가 export되지 않았다.

- [ ] **Step 3: `exchangeOAuthCode`를 추가한다**

`frontend/src/lib/api.ts`의 `logout` 바로 아래에 추가한다.

```ts
/**
 * 소셜 로그인 리다이렉트가 실어 온 일회용 코드를 액세스 토큰으로 바꾼다.
 * 코드도 토큰도 URL에 싣지 않는다 — 쿼리에 넣으면 액세스 로그에 남는다.
 * 실패는 이 흐름 안에서 안내하므로 전역 401 처리기를 타지 않게 request()를 쓰지 않는다.
 */
export async function exchangeOAuthCode(code: string): Promise<string> {
  const response = await fetch(`${API_BASE_URL}/api/auth/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  if (!response.ok) {
    throw new ApiError('소셜 로그인에 실패했습니다.', response.status);
  }
  const body = (await response.json()) as { accessToken: string };
  return body.accessToken;
}
```

- [ ] **Step 4: 앱이 코드를 읽어 교환하게 한다**

`frontend/src/App.tsx`의 import 목록에 `exchangeOAuthCode`를 추가한다(알파벳 순서 — `deleteMessage` 다음).

```ts
  deleteMessage,
  exchangeOAuthCode,
  getChatRooms,
```

OAuth 처리 `useEffect`(153행 부근)에서 아래 세 곳을 바꾼다. **나머지(워프 연출, 가드 타이머, 오류 안내)는 그대로 둔다.**

`const oauthToken = params.get('token');`을 이렇게 바꾼다.

```ts
    const oauthCode = params.get('code');
```

`if (oauthToken || errCode) {`와 `if (!oauthToken) return;`의 변수명을 맞춘다.

```ts
    // 해시 즉시 제거(코드가 URL/히스토리에 남지 않게)
    if (oauthCode || errCode) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
```

```ts
    if (!oauthCode) return;
```

`try` 블록 안의 `getMe(oauthToken)` 앞에 교환을 넣고, `persistSession`이 교환으로 받은 토큰을 쓰게 한다.

```ts
      try {
        const accessToken = await exchangeOAuthCode(oauthCode);
        const member = await getMe(accessToken);
        const elapsed = Date.now() - startedAt;
        if (elapsed < WARP_MIN_MS) {
          await new Promise((resolve) => setTimeout(resolve, WARP_MIN_MS - elapsed));
        }
        persistSession(accessToken, toUser(member));
      } catch (e) {
```

`catch` 블록의 안내 문구는 그대로 둔다 — 교환 실패도 "로그인 처리에 실패했어요"가 맞다.

- [ ] **Step 5: `#token=` 흔적이 남지 않았는지 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && grep -rn "token=" frontend/src src/main/java
```

기대: `#token=`을 읽거나 쓰는 코드가 없다. `#oauth_error=`는 남아 있어야 한다.

- [ ] **Step 6: 검증**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend/frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 7: 커밋**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git add frontend/src/lib/api.ts frontend/src/lib/api.test.ts frontend/src/App.tsx
git commit -m "feat(frontend): 소셜 로그인 코드를 토큰으로 교환"
```

---

### Task 4: 최종 검증과 PR

**Files:** 없음 (검증만)

- [ ] **Step 1: 백엔드 전체 테스트**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test --rerun-tasks
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

기대: 출력 없음

- [ ] **Step 4: `SecurityConfig`를 건드리지 않았는지 확인**

```bash
git diff develop -- src/main/java/com/example/springboot_realtimechat/config/SecurityConfig.java
```

기대: 출력 없음. `/api/auth/**`의 permitAll이 교환 엔드포인트를 덮는다.

- [ ] **Step 5: 코드 키가 한 곳에만 있는지 확인**

```bash
grep -rn "oauth:code" src/main/java
```

기대: `OAuthCodeStore.java`의 상수 하나만.

- [ ] **Step 6: 원자적 소비인지 확인**

```bash
grep -n -A 6 "public Long consume" src/main/java/com/example/springboot_realtimechat/security/OAuthCodeStore.java
```

기대: `getAndDelete` 한 번. `get` 후 `delete`로 나뉘어 있으면 Task 1로 돌아간다.

- [ ] **Step 7: 설계 §7 자동 테스트 목록과 대조**

| §7 항목 | 테스트 |
|---|---|
| 리다이렉트에 `#code=`가 실리고 JWT가 실리지 않는다 | `OAuth2SuccessHandlerTest.리다이렉트에_토큰이_아니라_코드를_싣는다` / `..._JWT가_들어가지_않는다` |
| 코드로 교환하면 액세스 토큰이 나온다 | `OAuthCodeExchangeTest.발급된_코드를_교환하면_액세스_토큰이_나온다` |
| 같은 코드 두 번째 교환은 401 | `OAuthCodeExchangeTest.같은_코드를_두_번_교환하면_두_번째는_401이다` |
| 존재하지 않는 코드는 401 | `OAuthCodeExchangeTest.존재하지_않는_코드는_401이다` |
| 만료된 코드는 401 | `OAuthCodeStoreTest.코드의_TTL은_60초다`(TTL 설정) + `존재하지_않는_코드는_null이다`(만료 후 동작) |
| 교환이 회원 단위 무효화 키를 지운다 | `OAuthCodeExchangeTest.교환이_회원_단위_무효화_키를_지운다` |
| 교환은 인증 없이 호출할 수 있다 | `OAuthCodeExchangeTest.교환은_인증_없이_호출할_수_있다` |
| 코드가 가리키는 회원이 없으면 401 | `OAuthCodeExchangeTest.코드가_가리키는_회원이_없으면_401이다` |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 8: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓴다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** 토큰 발급이 `OAuth2SuccessHandler`에서 `AuthService.exchangeOAuthCode`로 옮겨가면서 `clearMember`도 함께 옮겼다 — 한쪽에만 남으면 로그아웃 후 소셜 재로그인이 무효화 경계에 걸린다.

```bash
git push -u origin feat/oauth-code-exchange
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 9: 배포 후 실측 항목을 PR에 남긴다**

- 소셜 로그인 후 주소창에 토큰이 아니라 코드가 잠깐 보이는지(그리고 즉시 사라지는지)
- 그 코드를 복사해 다시 교환하면 401인지
- nginx 액세스 로그에 JWT 형태의 문자열이 더 이상 남지 않는지
- 구글·카카오 양쪽 모두 로그인이 되는지
- 로그아웃 → 소셜 재로그인이 곧바로 되는지(`clearMember` 이동 확인)

---

## Self-Review

**스펙 커버리지 (설계 §2·§3·§5·§7):**

| 요구 | 태스크 |
|---|---|
| D1 코드·TTL 60초·`memberId`만 저장 | Task 1 |
| D2 원자적 1회 소비(`GETDEL`) | Task 1, Task 4 Step 6에서 확인 |
| D3 `UUID.randomUUID()` | Task 1 |
| D4 `POST /api/auth/oauth/token`, 본문으로 수수, 인가 설정 불변 | Task 2, Task 4 Step 4에서 확인 |
| D5 발급과 `clearMember`가 함께 교환으로 이동 | Task 2, Task 4 Step 10에서 확인 |
| D6 `#token=` 제거 | Task 3 Step 5에서 확인 |
| D7 코드 저장 실패 시 `#oauth_error=` | Task 2 Step 6, 테스트 `코드_발급에_실패하면_오류로_리다이렉트한다` |
| D8 실패를 구분하지 않고 401 | Task 2 Step 4 |
| §5 코드 조회 실패는 500 | Task 1(예외를 삼키지 않음) |
| §7 자동 테스트 | Task 1·2, Task 4 Step 7에서 대조 |
| §7 프론트 검증 | Task 3 Step 6, Task 4 Step 2 |
