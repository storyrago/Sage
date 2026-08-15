# Google OAuth 로그인 (이메일/비번 공존) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 이메일/비밀번호 로그인을 유지한 채 Google 소셜 로그인을 추가하고, RDS 초기화 + 데모 계정/대화 시드로 로그인 즉시 기능이 다 보이게 한다.

**Architecture:** Spring Security `oauth2Login`(OIDC, 내장 Google). 콜백에서 `google_sub`(OIDC `sub`) 우선 → 검증된 이메일 연결 → 신규 생성으로 회원을 upsert하고, 기존과 동일한 JWT를 발급해 프론트에 `/#token=` 프래그먼트로 넘긴다. localStorage+Bearer+STOMP 모델 무변경.

**Tech Stack:** Spring Boot 4 / Spring Security OAuth2 Client / JPA(Hibernate 7) / MySQL / Flyway / React+TS(Vite).

## Global Constraints

- DB 스키마·시드는 **Flyway 마이그레이션 파일**로만 (V3=스키마, V4=시드). `ddl-auto: validate` 유지, 수동 ALTER 금지.
- **신원 매칭 순서**: `findByGoogleSub(sub)` → (없으면) `findByEmail(email)` + `email_verified==true`면 연결(google_sub 세팅) / `false`면 `EMAIL_ALREADY_REGISTERED` / (없으면) 신규 생성(password=null, provider=GOOGLE, google_sub=sub). `email`은 unique.
- JWT = `jwtTokenProvider.createAccessToken(Long memberId, String email)`. STOMP Principal 이름 = email(무변경).
- OAuth2 client 설정은 **더미 기본값**(`${GOOGLE_CLIENT_ID:dummy-client-id}` 등)으로 로컬/테스트 컨텍스트가 로드되게 하고, 실 크레덴셜은 env로.
- 백엔드 테스트: `@SpringBootTest`(H2, `MODE=MySQL`). 프론트: 테스트 러너 없음 → `npm run lint`(tsc) + `npm run build`.
- 브랜치 `feat/oauth-google`(develop 분기). 커밋 자주.
- **실 Google 로그인 화면 왕복은 자동화가 검증 불가**(자격증명 입력 금지) → 사용자 수동 검증. 자동화는 "구글 경계 직전(302 생성)"과 "핸드오프 이후(프론트)"까지.

---

## Task 1: 의존성 + OAuth/서버 설정

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/resources/application-local.example.yaml`

**Interfaces:**
- Produces: OAuth2 `ClientRegistrationRepository`(google) 빈, 설정 프로퍼티 `app.frontend-url`, `server.forward-headers-strategy: framework`.

- [ ] **Step 1: 의존성 추가**

`build.gradle`의 `dependencies { ... }` 안, `//spring security` 줄 아래에 추가:
```gradle
	//oauth2 client (Google 로그인)
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
```

- [ ] **Step 2: application.yaml에 OAuth·forward-headers·frontend-url 추가**

`src/main/resources/application.yaml`의 `spring:` 블록 안(예: `data:` 블록 뒤)에 추가:
```yaml
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:dummy-client-id}
            client-secret: ${GOOGLE_CLIENT_SECRET:dummy-client-secret}
            scope:
              - openid
              - email
              - profile
```
그리고 최상위(파일 맨 끝, `aws:` 블록과 같은 레벨)에 추가:
```yaml
server:
  forward-headers-strategy: framework   # nginx(X-Forwarded-*) 뒤에서 OAuth redirect_uri를 https 원본 host로 생성
app:
  frontend-url: ${FRONTEND_URL:http://localhost:5173}   # OAuth 성공 후 JWT 핸드오프 대상
```
(주의: `forward-headers-strategy`가 없으면 운영에서 redirect_uri가 `http://app:8080/...`로 잘못 만들어져 Google이 거부한다. 기존 `/api`·`/ws`는 절대URL을 안 만들어 문제없었음.)

- [ ] **Step 3: application-local.example.yaml에 예시 추가**

`src/main/resources/application-local.example.yaml` 맨 끝에 추가:
```yaml

  security:
    oauth2:
      client:
        registration:
          google:
            client-id: your-google-client-id
            client-secret: your-google-client-secret
            scope: [openid, email, profile]

app:
  frontend-url: http://localhost:5173
```

- [ ] **Step 4: 컨텍스트 로드 확인**

Run: `./gradlew test`
Expected: 기존 전체 테스트 그린(oauth2 client 설정이 더미값으로 로드되어 컨텍스트가 정상 기동). 실패 시 client-id/secret 기본값 누락 여부 확인.

- [ ] **Step 5: Commit**
```bash
git add build.gradle src/main/resources/application.yaml src/main/resources/application-local.example.yaml
git commit -m "build(oauth): spring-boot-starter-oauth2-client + Google 설정·forward-headers·frontend-url"
```

---

## Task 2: V3 스키마 + Member 엔티티 + repository

**Files:**
- Create: `src/main/resources/db/migration/V3__oauth_columns.sql`
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/Member.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MemberRepository.java`
- Test: `src/test/java/com/example/springboot_realtimechat/oauth/OAuthPersistenceTest.java` (create)

**Interfaces:**
- Produces: `Member.ofGoogle(email, nickname, imageUrl, googleSub): Member`, `Member.linkGoogle(String)`, `Member.updateEmail(String)`, `Member.getProvider()`, `Member.getGoogleSub()`; `MemberRepository.findByGoogleSub(String): Optional<Member>`. `password`는 nullable.

- [ ] **Step 1: V3 마이그레이션 작성**

`V3__oauth_columns.sql`:
```sql
ALTER TABLE members MODIFY password VARCHAR(255) NULL;
ALTER TABLE members ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE members ADD COLUMN google_sub VARCHAR(255) NULL;
ALTER TABLE members ADD CONSTRAINT uk_members_google_sub UNIQUE (google_sub);
```

- [ ] **Step 2: Member 엔티티 수정**

`Member.java` — `password` 필드의 `@Column(nullable = false)`를 제거해 nullable로:
```java
    @Column
    private String password;
```
`nickname` 필드 앞(또는 password 뒤)에 필드 추가:
```java
    @Column(nullable = false, length = 20)
    private String provider = "LOCAL";

    @Column(name = "google_sub", length = 255, unique = true)
    private String googleSub;
```
클래스 마지막 `}` 앞에 팩토리·메서드 추가:
```java
    public static Member ofGoogle(String email, String nickname, String profileImageUrl, String googleSub) {
        Member m = new Member();
        m.email = email;
        m.password = null;
        m.nickname = nickname;
        m.profileImageUrl = profileImageUrl;
        m.provider = "GOOGLE";
        m.googleSub = googleSub;
        return m;
    }

    public void linkGoogle(String googleSub) {
        this.googleSub = googleSub;
    }

    public void updateEmail(String email) {
        this.email = email;
    }
```
(`@Getter`가 이미 있어 `getProvider()`/`getGoogleSub()` 자동 생성. `new Member()`는 `@NoArgsConstructor`가 public으로 생성.)

- [ ] **Step 3: MemberRepository에 findByGoogleSub 추가**

`MemberRepository.java`:
```java
    Optional<Member> findByGoogleSub(String googleSub);
```

- [ ] **Step 4: 영속성 테스트 작성 후 실행(그린)**

`OAuthPersistenceTest.java`:
```java
package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OAuthPersistenceTest {
    @Autowired MemberRepository memberRepository;

    @Test
    void 구글회원_저장하고_sub로_조회() {
        Member saved = memberRepository.save(
                Member.ofGoogle("g@e.com", "구글이", "http://img", "sub-123"));

        Member found = memberRepository.findByGoogleSub("sub-123").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPassword()).isNull();
        assertThat(found.getProvider()).isEqualTo("GOOGLE");
    }
}
```
Run: `./gradlew test --tests "*OAuthPersistenceTest"` → PASS.

- [ ] **Step 5: 로컬 실제 MySQL로 V3 검증**
```bash
export PATH="/usr/local/mysql/bin:$PATH"
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS oauth_fresh; CREATE DATABASE oauth_fresh;"
JWT_SECRET=x SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/oauth_fresh' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=1111 \
./gradlew bootRun > /tmp/oauth.log 2>&1 &
# 부팅 후:
grep -iE "Successfully applied|Started Springboot" /tmp/oauth.log | tail
mysql -h 127.0.0.1 -uroot -p1111 -e "SHOW COLUMNS FROM members;" oauth_fresh
pkill -f SpringbootRealtimechatApplication
```
Expected: `Successfully applied 3 migrations`(V1+V2+V3), `Started …`(validate 통과), `password`가 YES(null), `provider`·`google_sub` 존재.

- [ ] **Step 6: Commit**
```bash
git add src/main/resources/db/migration/V3__oauth_columns.sql src/main/java/.../domain/Member.java src/main/java/.../repository/MemberRepository.java src/test/java/.../oauth/OAuthPersistenceTest.java
git commit -m "feat(oauth): members에 provider·google_sub + password nullable (Flyway V3)"
```

---

## Task 3: OAuthService — Google 사용자 upsert (핵심 로직, TDD)

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`
- Create: `src/main/java/com/example/springboot_realtimechat/service/OAuthService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/oauth/OAuthServiceTest.java` (create)

**Interfaces:**
- Consumes: `MemberRepository.findByGoogleSub`, `findByEmail`; `Member.ofGoogle`, `linkGoogle`, `updateEmail`.
- Produces: `OAuthService.upsertGoogleUser(String sub, String email, boolean emailVerified, String name, String picture): Member` — sub 우선 매칭/연결/생성. 미검증 이메일 충돌 시 `CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED)`.

- [ ] **Step 1: ErrorCode 추가**

`ErrorCode.java`의 `// Member` 그룹에 추가:
```java
    EMAIL_ALREADY_REGISTERED(409, "이미 가입된 이메일입니다. 이메일/비밀번호로 로그인해 주세요."),
    SOCIAL_LOGIN_ONLY(401, "소셜 로그인으로 가입된 계정입니다. 소셜 로그인을 이용해 주세요."),
```

- [ ] **Step 2: 실패 테스트 작성**

`OAuthServiceTest.java`:
```java
package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.OAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OAuthServiceTest {
    @Autowired OAuthService oAuthService;
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;

    @Test
    void 신규_구글사용자_생성() {
        Member m = oAuthService.upsertGoogleUser("sub-1", "new@g.com", true, "Alexander Longname", "http://p");
        assertThat(m.getId()).isNotNull();
        assertThat(m.getPassword()).isNull();
        assertThat(m.getProvider()).isEqualTo("GOOGLE");
        assertThat(m.getGoogleSub()).isEqualTo("sub-1");
        assertThat(m.getNickname()).isEqualTo("Alexander");   // 10자 절단
    }

    @Test
    void 같은_sub이면_이메일_달라도_동일회원() {
        Member first = oAuthService.upsertGoogleUser("sub-2", "a@g.com", true, "밥", "http://p");
        Member again = oAuthService.upsertGoogleUser("sub-2", "changed@g.com", true, "밥", "http://p");
        assertThat(again.getId()).isEqualTo(first.getId());   // sub로 동일인
        assertThat(again.getEmail()).isEqualTo("changed@g.com"); // 이메일 동기화
    }

    @Test
    void 검증된_이메일이_기존LOCAL회원과_같으면_연결() {
        Member local = memberService.create("link@g.com", "pw1234", "로컬");
        Member linked = oAuthService.upsertGoogleUser("sub-3", "link@g.com", true, "로컬", "http://p");
        assertThat(linked.getId()).isEqualTo(local.getId());  // 같은 계정에 연결
        assertThat(linked.getGoogleSub()).isEqualTo("sub-3");
        assertThat(memberRepository.count()).isEqualTo(1);    // 새 회원 안 생김
    }

    @Test
    void 미검증_이메일이_기존회원과_충돌하면_거부() {
        memberService.create("dup@g.com", "pw1234", "로컬");
        assertThatThrownBy(() -> oAuthService.upsertGoogleUser("sub-4", "dup@g.com", false, "누구", "http://p"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
}
```

- [ ] **Step 3: 실패 확인** — `./gradlew test --tests "*OAuthServiceTest"` → FAIL(`OAuthService` 없음, 컴파일 에러).

- [ ] **Step 4: 구현**

`OAuthService.java`:
```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OAuthService {
    private final MemberRepository memberRepository;

    @Transactional
    public Member upsertGoogleUser(String sub, String email, boolean emailVerified, String name, String picture) {
        // 1) sub 우선 — 이메일이 바뀌었어도 동일인
        Member bySub = memberRepository.findByGoogleSub(sub).orElse(null);
        if (bySub != null) {
            if (email != null && !email.equals(bySub.getEmail())) {
                bySub.updateEmail(email);
            }
            return bySub;
        }
        // 2) 이메일로 기존 회원 — 검증된 이메일에 한해 연결
        Member byEmail = (email != null) ? memberRepository.findByEmail(email).orElse(null) : null;
        if (byEmail != null) {
            if (!emailVerified) {
                throw new CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            byEmail.linkGoogle(sub);
            return byEmail;
        }
        // 3) 신규 생성
        Member created = Member.ofGoogle(email, toNickname(name, email), picture, sub);
        return memberRepository.save(created);
    }

    private String toNickname(String name, String email) {
        String base;
        if (name != null && !name.isBlank()) {
            base = name.trim();
        } else if (email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@'));
        } else {
            base = "user";
        }
        String cut = base.length() > 10 ? base.substring(0, 10) : base;
        return cut.trim();   // "Alexander Longname" → 앞 10자 "Alexander " → trim → "Alexander"
    }
}
```

- [ ] **Step 5: 통과 확인** — `./gradlew test --tests "*OAuthServiceTest"` → PASS(4/4).

- [ ] **Step 6: Commit**
```bash
git add src/main/java/.../global/exception/ErrorCode.java src/main/java/.../service/OAuthService.java src/test/java/.../oauth/OAuthServiceTest.java
git commit -m "feat(oauth): OAuthService.upsertGoogleUser (sub 우선 매칭·연결·생성)"
```

---

## Task 4: 쿠키 기반 AuthorizationRequestRepository

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/security/HttpCookieOAuth2AuthorizationRequestRepository.java`

**Interfaces:**
- Produces: `HttpCookieOAuth2AuthorizationRequestRepository`(@Component) — STATELESS 환경에서 OAuth2 authorization request를 짧은 수명 쿠키로 콜백까지 넘긴다.

- [ ] **Step 1: 구현**

`HttpCookieOAuth2AuthorizationRequestRepository.java`:
```java
package com.example.springboot_realtimechat.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_auth_request";
    private static final int COOKIE_MAX_AGE = 180; // 3분

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookie(request).map(this::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(request, response);
            return;
        }
        String value = Base64.getUrlEncoder().encodeToString(serialize(authorizationRequest));
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());   // https(운영)에서만 Secure, http(로컬)에선 false
        cookie.setMaxAge(COOKIE_MAX_AGE);
        response.addCookie(cookie);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest req = loadAuthorizationRequest(request);
        deleteCookie(request, response);
        return req;
    }

    private Optional<Cookie> getCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .findFirst();
    }

    private void deleteCookie(HttpServletRequest request, HttpServletResponse response) {
        getCookie(request).ifPresent(c -> {
            Cookie del = new Cookie(COOKIE_NAME, "");
            del.setPath("/");
            del.setHttpOnly(true);
            del.setMaxAge(0);
            response.addCookie(del);
        });
    }

    private byte[] serialize(OAuth2AuthorizationRequest obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("authorization request 직렬화 실패", e);
        }
    }

    private OAuth2AuthorizationRequest deserialize(Cookie cookie) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getUrlDecoder().decode(cookie.getValue()));
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (OAuth2AuthorizationRequest) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인** — `./gradlew compileJava` → 성공.

- [ ] **Step 3: Commit**
```bash
git add src/main/java/.../security/HttpCookieOAuth2AuthorizationRequestRepository.java
git commit -m "feat(oauth): 쿠키 기반 AuthorizationRequestRepository (STATELESS 대응)"
```

---

## Task 5: OAuth 보안 배선 (OidcUserService + 핸들러 + SecurityConfig)

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/security/CustomOidcUserService.java`
- Create: `src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java`
- Create: `src/main/java/com/example/springboot_realtimechat/security/OAuth2FailureHandler.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/config/SecurityConfig.java`

**Interfaces:**
- Consumes: `OAuthService.upsertGoogleUser`, `MemberRepository.findByGoogleSub`, `JwtTokenProvider.createAccessToken`, `HttpCookieOAuth2AuthorizationRequestRepository`, `app.frontend-url`.
- Produces: `oauth2Login`이 배선된 SecurityFilterChain. 진입점 `/oauth2/authorization/google`, 콜백 `/login/oauth2/code/google`. 성공 → `${frontend-url}/#token=`, 실패 → `${frontend-url}/#oauth_error=`.

- [ ] **Step 1: CustomOidcUserService**

`CustomOidcUserService.java`:
```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.service.OAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {
    private final OAuthService oAuthService;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        String sub = oidcUser.getSubject();
        String email = oidcUser.getEmail();
        boolean emailVerified = Boolean.TRUE.equals(oidcUser.getEmailVerified());
        String name = oidcUser.getFullName();
        String picture = oidcUser.getPicture();
        try {
            oAuthService.upsertGoogleUser(sub, email, emailVerified, name, picture);
        } catch (CustomException e) {
            // 실패 핸들러가 #oauth_error=<코드>로 안내
            throw new OAuth2AuthenticationException(new OAuth2Error(e.getErrorCode().name()), e.getMessage(), e);
        }
        return oidcUser;
    }
}
```

- [ ] **Step 2: OAuth2SuccessHandler**

`OAuth2SuccessHandler.java`:
```java
package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {
    private final MemberRepository memberRepository;
    private final com.example.springboot_realtimechat.security.JwtTokenProvider jwtTokenProvider;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        var member = memberRepository.findByGoogleSub(oidcUser.getSubject())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        response.sendRedirect(frontendUrl + "/#token=" + token);
    }
}
```

- [ ] **Step 3: OAuth2FailureHandler**

`OAuth2FailureHandler.java`:
```java
package com.example.springboot_realtimechat.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String code = "oauth_failed";
        if (exception instanceof OAuth2AuthenticationException oae
                && oae.getError() != null && oae.getError().getErrorCode() != null) {
            code = oae.getError().getErrorCode();
        }
        response.sendRedirect(frontendUrl + "/#oauth_error=" + code);
    }
}
```

- [ ] **Step 4: SecurityConfig 배선**

`SecurityConfig.java` — 필드에 신규 빈 주입(생성자는 `@RequiredArgsConstructor`가 생성):
```java
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomOidcUserService customOidcUserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository;
```
`authorizeHttpRequests`의 permitAll 목록에 추가:
```java
                                "/oauth2/**",        // OAuth 진입
                                "/login/oauth2/**",  // OAuth 콜백
```
그리고 `.addFilterBefore(...)` **앞에** oauth2Login 배선 추가:
```java
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a
                                .authorizationRequestRepository(cookieAuthorizationRequestRepository))
                        .userInfoEndpoint(u -> u.oidcUserService(customOidcUserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler)
                )
```
imports: `com.example.springboot_realtimechat.security.{CustomOidcUserService, OAuth2SuccessHandler, OAuth2FailureHandler, HttpCookieOAuth2AuthorizationRequestRepository}`.

- [ ] **Step 5: 전체 회귀 + "구글 경계까지" 실측**

Run: `./gradlew test`  → 전체 그린(컨텍스트가 oauth2Login까지 배선되어 로드).

수동 실측(진입 302가 Google로 향하는지 — 실제 로그인 불필요):
```bash
export PATH="/usr/local/mysql/bin:$PATH"
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS oauth_fresh; CREATE DATABASE oauth_fresh;"
JWT_SECRET=x GOOGLE_CLIENT_ID=test-id GOOGLE_CLIENT_SECRET=test-secret \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/oauth_fresh' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=1111 \
./gradlew bootRun > /tmp/oauth.log 2>&1 &
# 부팅 후:
curl -si "http://localhost:8080/oauth2/authorization/google" | grep -iE "^HTTP/|^location:"
pkill -f SpringbootRealtimechatApplication
```
Expected: `HTTP/1.1 302`, `Location: https://accounts.google.com/o/oauth2/v2/auth?...client_id=test-id...redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Flogin%2Foauth2%2Fcode%2Fgoogle...` (진입·쿠키 저장·redirect_uri 생성이 정상이라는 증거).

- [ ] **Step 6: Commit**
```bash
git add src/main/java/.../security/CustomOidcUserService.java src/main/java/.../security/OAuth2SuccessHandler.java src/main/java/.../security/OAuth2FailureHandler.java src/main/java/.../config/SecurityConfig.java
git commit -m "feat(oauth): oauth2Login 배선 (OidcUserService·성공/실패 핸들러·쿠키 repo)"
```

---

## Task 6: 이메일/비번 로그인 — 소셜 전용 계정 가드

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/AuthService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/oauth/OAuthServiceTest.java` (add)

**Interfaces:**
- Consumes: `Member` password nullable, `AuthService.login`.
- Produces: password==null 계정에 이메일/비번 로그인 시 `INVALID_PASSWORD`(예외 없이 거부).

- [ ] **Step 1: 실패 테스트 추가**

`OAuthServiceTest.java`에 필드·테스트 추가:
```java
    @Autowired com.example.springboot_realtimechat.service.AuthService authService;

    @Test
    void 소셜전용계정_비번로그인_거부() {
        oAuthService.upsertGoogleUser("sub-9", "social@g.com", true, "소셜", "http://p"); // password=null

        var req = new com.example.springboot_realtimechat.dto.LoginRequest();
        req.setEmail("social@g.com");      // LoginRequest는 @Getter @Setter (email/password)
        req.setPassword("whatever");

        assertThatThrownBy(() -> authService.login(req, "1.2.3.4"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PASSWORD);
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "*OAuthServiceTest"` → FAIL(password null에 `passwordEncoder.matches` 호출 → `IllegalArgumentException`, 즉 원하는 `CustomException`이 아님).

- [ ] **Step 3: 구현 — null 가드**

`AuthService.login`의 조건문을 다음으로:
```java
        if (member == null || member.getPassword() == null
                || !passwordEncoder.matches(loginRequest.getPassword(), member.getPassword())) {
            loginRateLimiter.recordFailure(clientIp);
            throw new CustomException(member == null ? ErrorCode.MEMBER_NOT_FOUND : ErrorCode.INVALID_PASSWORD);
        }
```
(`member.getPassword() == null`을 `matches` 앞에 추가.)

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests "*OAuthServiceTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/.../service/AuthService.java src/test/java/.../oauth/OAuthServiceTest.java
git commit -m "feat(oauth): 소셜 전용 계정(비번 null) 이메일 로그인 거부 가드"
```

---

## Task 7: V4 데모 시드

**Files:**
- Create: `src/main/resources/db/migration/V4__seed_demo.sql`

**Interfaces:**
- Produces: 데모 회원 2(`demo@demo.com`/`guest@demo.com`, 비번 `demo1234`), 방 2(공지·잡담), 멤버십·메시지. `demo` 입장에서 잡담방 안읽음 3.

- [ ] **Step 1: 시드 작성**

`V4__seed_demo.sql`:
```sql
-- 데모 계정 (비번 demo1234, BCrypt strength 10). provider=LOCAL.
INSERT INTO members (id, email, password, nickname, provider, created_at) VALUES
  (1, 'demo@demo.com',  '$2y$10$./I/HRInW7FBcewDKVwYCumiJMCjP2bILGD4jT6jTwf6ihDGdxmiK', '데모',   'LOCAL', NOW(6)),
  (2, 'guest@demo.com', '$2y$10$./I/HRInW7FBcewDKVwYCumiJMCjP2bILGD4jT6jTwf6ihDGdxmiK', '게스트', 'LOCAL', NOW(6));

-- 방
INSERT INTO chatrooms (id, name, created_at) VALUES
  (1, '공지', NOW(6)),
  (2, '잡담', NOW(6));

-- 방1(공지) 메시지 id 1~3
INSERT INTO messages (id, content, member_id, chatroom_id, created_at, deleted) VALUES
  (1, '샘플 채팅에 오신 걸 환영합니다.', 2, 1, NOW(6), 0),
  (2, '여기는 공지 채널이에요.',          1, 1, NOW(6), 0),
  (3, '무엇이든 편하게 남겨주세요.',      2, 1, NOW(6), 0);

-- 방2(잡담) 메시지 id 4~9 (5번은 4번 답장)
INSERT INTO messages (id, content, member_id, chatroom_id, created_at, reply_to_id, deleted) VALUES
  (4, '오늘 점심 뭐 먹지?',  2, 2, NOW(6), NULL, 0),
  (5, '김치찌개 어때요',     1, 2, NOW(6), 4,    0),
  (6, '좋아요',              2, 2, NOW(6), NULL, 0),
  (7, '2시에 회의 있어요',   2, 2, NOW(6), NULL, 0),
  (8, '넵 참고할게요',       1, 2, NOW(6), NULL, 0),
  (9, '다들 수고하셨습니다', 2, 2, NOW(6), NULL, 0);

-- 멤버십 + 읽음 포인터
--  demo(1): 공지 다 읽음(3), 잡담은 5까지만 읽음 → 잡담 안읽음 = id>5 & 남이 보냄 & !삭제 = {6,7,9} = 3
--  guest(2): 둘 다 다 읽음
INSERT INTO chatroom_members (id, member_id, chatroom_id, last_read_message_id) VALUES
  (1, 1, 1, 3),
  (2, 2, 1, 3),
  (3, 1, 2, 5),
  (4, 2, 2, 9);
```

- [ ] **Step 2: 로컬 MySQL fresh 부팅으로 V1~V4 검증**
```bash
export PATH="/usr/local/mysql/bin:$PATH"
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS oauth_seed; CREATE DATABASE oauth_seed;"
JWT_SECRET=x SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/oauth_seed' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=1111 \
./gradlew bootRun > /tmp/oauth.log 2>&1 &
# 부팅 후:
grep -iE "Successfully applied|Started Springboot" /tmp/oauth.log | tail
mysql -h 127.0.0.1 -uroot -p1111 -e "SELECT COUNT(*) members FROM members; SELECT COUNT(*) msgs FROM messages;" oauth_seed
# demo(1)의 잡담(2) 안읽음이 3인지 (안읽음 정의: id>last_read & member!=me & !deleted)
mysql -h 127.0.0.1 -uroot -p1111 -e "SELECT COUNT(*) unread FROM messages WHERE chatroom_id=2 AND id>5 AND member_id<>1 AND deleted=0;" oauth_seed
pkill -f SpringbootRealtimechatApplication
```
Expected: `Successfully applied 4 migrations`, `Started …`, members=2, msgs=9, unread=3.

- [ ] **Step 3: Commit**
```bash
git add src/main/resources/db/migration/V4__seed_demo.sql
git commit -m "feat(oauth): 데모 계정·대화 시드 (Flyway V4) — 로그인 즉시 기능 노출"
```

---

## Task 8: 프론트 — Welcome에 Google 버튼 + OAuth 에러 표시 + env

**Files:**
- Modify: `frontend/src/components/Welcome.tsx`
- Modify: `frontend/.env.example`

**Interfaces:**
- Consumes: `import.meta.env.VITE_OAUTH_BASE`.
- Produces: `WelcomeProps`에 `oauthError?: string | null`. "Google로 로그인" 버튼이 `${VITE_OAUTH_BASE ?? ''}/oauth2/authorization/google`로 전체 이동. `oauthError`가 있으면 로그인 카드에 안내 표시.

- [ ] **Step 1: env 예시 추가**

`frontend/.env.example` 맨 끝에 추가:
```
# OAuth 진입 베이스. dev는 백엔드 오리진 직행(프록시 우회), prod는 same-origin(빈값)
VITE_OAUTH_BASE="http://localhost:8080"
```

- [ ] **Step 2: Welcome에 prop·버튼·에러 표시 추가**

`Welcome.tsx`:
- `WelcomeProps`(인터페이스)에 추가: `oauthError?: string | null;`
- 구조분해에 `oauthError` 추가: `export default function Welcome({ onComplete, initialUser, warping, oauthError }: WelcomeProps) {`
- 컴포넌트 함수 내 상단(다른 상수 근처)에 추가:
```tsx
  const OAUTH_BASE = (import.meta.env.VITE_OAUTH_BASE as string | undefined) ?? '';
  const handleGoogleLogin = () => {
    window.location.href = `${OAUTH_BASE}/oauth2/authorization/google`;
  };
```
- 로그인 `<form ...>...</form>` **바로 뒤**에 구분선 + Google 버튼 추가:
```tsx
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '14px 0' }}>
              <div style={{ flex: 1, height: 1, background: '#2a322d' }} />
              <span style={{ fontSize: 12, color: '#6B7972' }}>또는</span>
              <div style={{ flex: 1, height: 1, background: '#2a322d' }} />
            </div>
            <button type="button" onClick={handleGoogleLogin}
              style={{ width: '100%', background: '#fff', color: '#1f2937', borderRadius: 13, padding: 13, fontWeight: 600, fontSize: 14, border: '1px solid #d0d7de', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
              <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.71-1.57 2.68-3.89 2.68-6.62z"/>
                <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.83.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"/>
                <path fill="#FBBC05" d="M3.97 10.72a5.4 5.4 0 0 1 0-3.44V4.95H.96a9 9 0 0 0 0 8.1l3.01-2.33z"/>
                <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.47.9 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z"/>
              </svg>
              Google로 로그인
            </button>
```
- `oauthError` 표시: 로그인 카드 내 기존 에러 표시부(있다면 그 근처)에, 없으면 form 위에 추가:
```tsx
            {oauthError && (
              <div style={{ marginBottom: 10, color: '#e88', fontSize: 13 }}>{oauthError}</div>
            )}
```
(기존 `errorCode` 표시 스타일이 있으면 그 패턴을 재사용.)

- [ ] **Step 3: 검증 + Commit**
```bash
cd frontend && npm run lint && npm run build   # exit 0
git add frontend/src/components/Welcome.tsx frontend/.env.example
git commit -m "feat(oauth): Welcome에 Google 로그인 버튼 + OAuth 에러 표시"
```

---

## Task 9: 프론트 — App 해시 핸드오프

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `getMe`, `toUser`, `persistSession`, Welcome prop `oauthError`.
- Produces: 마운트 시 `location.hash`의 `token`/`oauth_error` 처리. `token` → `getMe`→`persistSession`→해시 제거. `oauth_error` → `oauthError` 상태 세팅 후 `<Welcome oauthError=... />`.

- [ ] **Step 1: 상태 + 핸드오프 effect 추가**

`App.tsx`:
- 상태 추가(다른 useState 근처):
```tsx
  const [oauthError, setOauthError] = useState<string | null>(null);
```
- 초기 세션 로드 effect(`localStorage.getItem(SESSION_KEY)`) **바로 위 또는 아래**에 새 effect 추가:
```tsx
  useEffect(() => {
    const hash = window.location.hash;
    if (!hash) return;
    const params = new URLSearchParams(hash.slice(1));
    const oauthToken = params.get('token');
    const errCode = params.get('oauth_error');
    // 해시 즉시 제거(토큰이 URL/히스토리에 남지 않게)
    if (oauthToken || errCode) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    if (errCode) {
      setOauthError(
        errCode === 'EMAIL_ALREADY_REGISTERED'
          ? '이미 가입된 이메일이에요. 이메일/비밀번호로 로그인해 주세요.'
          : '구글 로그인에 실패했어요. 다시 시도해 주세요.',
      );
      return;
    }
    if (!oauthToken) return;
    (async () => {
      try {
        const member = await getMe(oauthToken);
        persistSession(oauthToken, toUser(member));
      } catch (e) {
        console.error('[OAuth] 핸드오프 실패:', e);
        setOauthError('로그인 처리에 실패했어요. 다시 시도해 주세요.');
      }
    })();
  }, [persistSession]);
```
(import에 `getMe`·`toUser`는 이미 있음. `persistSession`도 스코프에 있음.)

- [ ] **Step 2: Welcome에 prop 전달**

`<Welcome ... />`에 `oauthError={oauthError}` 추가.

- [ ] **Step 3: 검증 + Commit**
```bash
cd frontend && npm run lint && npm run build   # exit 0
git add frontend/src/App.tsx
git commit -m "feat(oauth): App 해시 핸드오프(#token/#oauth_error)"
```

---

## Task 10: nginx — OAuth 경로 프록시

**Files:**
- Modify: `frontend/nginx.conf`

**Interfaces:**
- Produces: `/oauth2/`·`/login/oauth2/`를 백엔드(app:8080)로 프록시. (운영 same-origin OAuth 왕복용.)

- [ ] **Step 1: location 추가**

`frontend/nginx.conf`의 `location /api/ { ... }` 블록 **뒤에** 추가(같은 proxy 헤더 세트):
```nginx
  # OAuth2 진입·콜백 → Spring (app 컨테이너 8080)
  location /oauth2/ {
    proxy_pass http://app:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
  location /login/oauth2/ {
    proxy_pass http://app:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
```
(`X-Forwarded-Proto $scheme`가 Task 1의 `forward-headers-strategy: framework`와 짝을 이뤄 redirect_uri를 https로 생성.)

- [ ] **Step 2: Commit**
```bash
git add frontend/nginx.conf
git commit -m "feat(oauth): nginx에 /oauth2·/login/oauth2 프록시 추가"
```
(주의: nginx 문법 검증은 배포 시 컨테이너에서 `nginx -t`로. 로컬 검증 불가면 배포 체크리스트에 포함.)

---

## Task 11: 통합 검증 + 문서 + PR

- [ ] **Step 1: 백엔드 전체** — `./gradlew test` 그린. Task 2/7 방식으로 로컬 MySQL fresh V1~V4 부팅 재확인(validate 통과 + 데모 데이터).
- [ ] **Step 2: 프론트** — `cd frontend && npm run lint && npm run build` exit 0.
- [ ] **Step 3: 핸드오프 실측(모의 토큰, 실 백엔드)** — 백엔드 기동(oauth_seed DB) + 프론트 dev 기동. 브라우저에서 `demo@demo.com`/`demo1234`로 로그인해 데모 시드(공지·잡담, 잡담에 안읽음 소인 3)가 보이는지 육안 확인. 이어서 유효 JWT를 `location.href = '/#token=' + <토큰>`으로 심어 새로고침 → 자동 로그인·해시 제거되는지 확인(해시 핸드오프 경로 검증). `#oauth_error=EMAIL_ALREADY_REGISTERED`도 심어 안내 문구 확인.
- [ ] **Step 4: 스펙 문서 최신화** — 구현 중 벗어난 결정 있으면 `docs/superpowers/specs/2026-07-25-oauth-google-design.md`에 정정.
- [ ] **Step 5: PR → develop** (CLAUDE.md의 `.github/pull_request_template.md` 5섹션 형식). 본문 "배포 영향"에 **사용자 수동 체크리스트** 명시:
  1. **Google Cloud Console**: OAuth 클라이언트 생성, 승인 redirect URI = `http://localhost:8080/login/oauth2/code/google` + `https://sagertc.duckdns.org/login/oauth2/code/google`, 동의화면 scope openid/email/profile.
  2. **EC2 `.env`**: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `FRONTEND_URL=https://sagertc.duckdns.org` 추가.
  3. **RDS 초기화**: 스키마 drop+recreate(또는 flyway_schema_history 포함 전체 초기화) → 재배포 시 Flyway V1~V4 fresh 적용(깨끗+데모 시드).
  4. **nginx 반영**: 새 `frontend/nginx.conf`가 배포되고 컨테이너에서 `nginx -t` 통과.
  5. **실 구글 로그인 왕복**: 배포 후 라이브에서 "Google로 로그인" 직접 확인(자동화 미검증 구간).
  ```bash
  git push -u origin feat/oauth-google
  gh pr create --base develop --head feat/oauth-google --title "feat(oauth): Google 로그인 (이메일/비번 공존)" --body-file <PR본문>
  ```

---

## Self-Review 결과

- **Spec coverage**: §1 데이터모델→T2, §2 백엔드흐름/매칭→T3·T5, §2.3 쿠키repo→T4, §3 프론트(버튼 T8·핸드오프 T9), §4 초기화·시드→T7(초기화는 배포 체크리스트 T11), §5 배포(Google Console·env·nginx)→T1·T10·T11, §6 검증→각 태스크+T11, §7 엣지(비번null가드 T6·미검증차단 T3·닉네임절단 T3)→반영. 커버 확인.
- **Placeholder scan**: 없음(코드·SQL·명령 제시). BCrypt 해시는 실제 값(`$2y$10$./I/...`) 사용. PR 본문 `<PR본문>`은 작성 지시.
- **Type consistency**: `upsertGoogleUser(sub, email, emailVerified, name, picture)` T3 정의↔T5 호출 일치. `findByGoogleSub` T2↔T5 일치. `createAccessToken(memberId, email)` 기존↔T5 일치. `oauthError` prop T8(Welcome)↔T9(App) 일치. `VITE_OAUTH_BASE` T8 사용. ErrorCode `EMAIL_ALREADY_REGISTERED` T3 정의↔T5·T9 사용 일치.
