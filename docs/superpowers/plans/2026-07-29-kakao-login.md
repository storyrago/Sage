# 카카오 로그인 (신원 모델 일반화) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 카카오 소셜 로그인을 추가하고, 신원을 `(provider, provider_id)`로 일반화하며 이메일을 선택 속성으로 전환한다.

**Architecture:** 카카오도 OIDC를 활성화했으므로 구글과 **동일한 `OidcUserService` 경로**를 탄다. `CustomOidcUserService`가 `registrationId`로 provider를 판별해 provider 중립 서비스(`OAuthService.upsertOidcUser`)에 위임한다. DB는 Flyway V5로 `google_sub` → `provider_id` 이관 + `email` nullable 전환. STOMP Principal은 변하는 값(email)에서 불변 값(member id)으로 옮긴다.

**Tech Stack:** Spring Boot / Spring Security OAuth2 Client (OIDC) / JPA(`ddl-auto: validate`) / Flyway / MySQL 8 · H2(test) / React + TypeScript(Vite)

설계 문서: `docs/superpowers/specs/2026-07-29-kakao-login-design.md`

## Global Constraints

- DB 스키마 변경은 **Flyway 마이그레이션 파일로만**(`src/main/resources/db/migration/V*.sql`). 수동 ALTER 금지. `ddl-auto: validate` 유지.
- 테스트는 H2 create-drop이라 Flyway 비활성(`src/test/resources/application.yaml`).
- OAuth client 설정은 **더미 기본값**(`${KAKAO_CLIENT_ID:dummy-client-id}`)을 둬서 로컬/테스트 컨텍스트가 로드되게 한다.
- 카카오 provider는 **`issuer-uri`를 쓰지 않는다.** `issuer-uri`는 애플리케이션 부팅 시 discovery 문서를 네트워크로 가져오므로, 네트워크가 없는 CI·테스트에서 컨텍스트 로드가 실패한다. 엔드포인트를 명시한다.
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- 검증 명령: 백엔드 `./gradlew test`, 프론트 `cd frontend && npm run lint && npm run build`.
- 새 환경변수는 EC2 `.env` + `docker-compose.yml`의 `app.environment` **양쪽**에 넣는다.

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `src/main/resources/db/migration/V5__generalize_provider_identity.sql` | 스키마 이관 | 생성 |
| `domain/Member.java` | 회원 엔티티 — provider 중립 신원 | 수정 |
| `repository/MemberRepository.java` | 조회 — provider+providerId | 수정 |
| `service/OAuthService.java` | 소셜 회원 upsert + 이메일 저장 규칙 | 수정 |
| `security/CustomUserDetails.java` | Principal 식별자 | 수정 |
| `redis/RedisSubscriber.java` | 안읽음 개인 큐 대상 | 수정 |
| `security/CustomOidcUserService.java` | provider 판별 → 서비스 위임 | 수정 |
| `security/OAuth2SuccessHandler.java` | 회원 조회 → JWT 발급 | 수정 |
| `src/main/resources/application.yaml` | 카카오 registration/provider | 수정 |
| `frontend/src/components/Welcome.tsx` | 카카오 버튼 | 수정 |
| `docker-compose.yml` | KAKAO_* 전달 | 수정 |
| `src/test/.../oauth/OAuthServiceTest.java` | upsert 규칙 테스트 | 수정 |
| `src/test/.../oauth/OAuthPersistenceTest.java` | 영속성 테스트 | 수정 |

---

## Task 1: V5 마이그레이션 + Member 엔티티 + Repository

**Files:**
- Create: `src/main/resources/db/migration/V5__generalize_provider_identity.sql`
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/Member.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MemberRepository.java`
- Test: `src/test/java/com/example/springboot_realtimechat/oauth/OAuthPersistenceTest.java`

**Interfaces:**
- Produces:
  - `Member.getProviderId(): String`
  - `Member.ofSocial(String provider, String providerId, String email, String nickname, String profileImageUrl): Member`
  - `Member.updateEmail(String email): void` (기존 유지)
  - `MemberRepository.findByProviderAndProviderId(String provider, String providerId): Optional<Member>`
- 제거: `Member.googleSub` 필드, `Member.ofGoogle(...)`, `Member.linkGoogle(...)`, `MemberRepository.findByGoogleSub(...)`
  - `linkGoogle`은 설계 D1(이메일 충돌 시 거부, 자동 연결 안 함)에 따라 호출자가 사라진다.

- [ ] **Step 1: V5 마이그레이션 작성**

`src/main/resources/db/migration/V5__generalize_provider_identity.sql`:

```sql
-- 신원을 provider 중립으로 일반화하고 이메일을 선택 속성으로 전환
ALTER TABLE members MODIFY email VARCHAR(255) NULL;
ALTER TABLE members ADD COLUMN provider_id VARCHAR(255) NULL;

-- 기존 구글 회원의 신원 값 이관
UPDATE members SET provider_id = google_sub WHERE google_sub IS NOT NULL;

ALTER TABLE members DROP INDEX uk_members_google_sub;
ALTER TABLE members DROP COLUMN google_sub;
ALTER TABLE members ADD CONSTRAINT uk_members_provider UNIQUE (provider, provider_id);
```

- [ ] **Step 2: Member 엔티티 수정**

`domain/Member.java`에서 `@Table` 애노테이션, `email`·`googleSub` 필드, `ofGoogle`·`linkGoogle` 메서드를 아래로 교체한다. 다른 필드(`id`, `password`, `provider`, `nickname`, `profileImageUrl`, `createdAt`, 연관관계)와 생성자 `Member(String, String, String)`, `updateProfileImageUrl`, `updateEmail`은 그대로 둔다.

```java
@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_members_provider",
                columnNames = {"provider", "provider_id"}
        )
)
public class Member {
```

`email` 필드(nullable로):

```java
    @Column(unique = true)
    private String email;
```

`googleSub` 필드를 아래로 교체:

```java
    @Column(name = "provider_id", length = 255)
    private String providerId;
```

`ofGoogle` / `linkGoogle`을 아래로 교체:

```java
    public static Member ofSocial(String provider, String providerId, String email,
                                  String nickname, String profileImageUrl) {
        Member m = new Member();
        m.provider = provider;
        m.providerId = providerId;
        m.email = email;
        m.password = null;
        m.nickname = nickname;
        m.profileImageUrl = profileImageUrl;
        return m;
    }
```

- [ ] **Step 3: MemberRepository 수정**

`repository/MemberRepository.java`의 `findByGoogleSub`를 교체:

```java
public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);

    Optional<Member> findByProviderAndProviderId(String provider, String providerId);
}
```

- [ ] **Step 4: 영속성 테스트 수정**

`src/test/java/com/example/springboot_realtimechat/oauth/OAuthPersistenceTest.java`의 본문 테스트를 아래로 교체한다(클래스 애노테이션·필드 주입은 기존 유지).

```java
    @Test
    void 소셜회원_저장_후_provider와_providerId로_조회된다() {
        memberRepository.save(
                Member.ofSocial("GOOGLE", "sub-123", "g@e.com", "구글이", "http://img"));

        Member found = memberRepository.findByProviderAndProviderId("GOOGLE", "sub-123").orElseThrow();

        assertThat(found.getEmail()).isEqualTo("g@e.com");
        assertThat(found.getPassword()).isNull();
        assertThat(found.getProvider()).isEqualTo("GOOGLE");
        assertThat(found.getProviderId()).isEqualTo("sub-123");
    }

    @Test
    void 이메일_없이도_소셜회원을_저장할_수_있다() {
        memberRepository.save(
                Member.ofSocial("KAKAO", "kakao-1", null, "카카오", null));

        Member found = memberRepository.findByProviderAndProviderId("KAKAO", "kakao-1").orElseThrow();

        assertThat(found.getEmail()).isNull();
    }
```

- [ ] **Step 5: 컴파일 확인 (아직 실패해도 정상)**

Run: `./gradlew compileJava`
Expected: FAIL — `OAuthService`, `OAuth2SuccessHandler`, `CustomOidcUserService`가 아직 `findByGoogleSub`/`ofGoogle`/`linkGoogle`을 참조한다. Task 2~4에서 해소된다.

- [ ] **Step 6: 로컬 MySQL fresh 부팅으로 V1~V5 검증**

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS kakao_mig; CREATE DATABASE kakao_mig CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

이 단계는 Task 4까지 끝나 컴파일이 통과한 뒤에 수행한다. Task 5 Step 3에서 실제 부팅 검증을 하므로, 여기서는 DB 생성만 준비해 둔다.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V5__generalize_provider_identity.sql \
        src/main/java/com/example/springboot_realtimechat/domain/Member.java \
        src/main/java/com/example/springboot_realtimechat/repository/MemberRepository.java \
        src/test/java/com/example/springboot_realtimechat/oauth/OAuthPersistenceTest.java
git commit -m "feat(oauth): 신원 모델을 provider·provider_id로 일반화 (Flyway V5)"
```

---

## Task 2: OAuthService provider 중립화 (TDD)

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/OAuthService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/oauth/OAuthServiceTest.java`

**Interfaces:**
- Consumes: `Member.ofSocial(...)`, `Member.updateEmail(...)`, `MemberRepository.findByProviderAndProviderId(...)`, `MemberRepository.findByEmail(...)` (Task 1)
- Produces: `OAuthService.upsertOidcUser(String provider, String providerId, String email, boolean emailVerified, String nickname, String picture): Member`
- 제거: `OAuthService.upsertGoogleUser(...)`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/oauth/OAuthServiceTest.java`를 아래 내용으로 **전체 교체**한다.

```java
package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
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
    @Autowired MemberRepository memberRepository;

    @Test
    void 신규_소셜사용자_생성() {
        Member m = oAuthService.upsertOidcUser("GOOGLE", "sub-1", "new@g.com", true, "Alexander Longname", "http://p");

        assertThat(m.getId()).isNotNull();
        assertThat(m.getPassword()).isNull();
        assertThat(m.getProvider()).isEqualTo("GOOGLE");
        assertThat(m.getProviderId()).isEqualTo("sub-1");
        assertThat(m.getEmail()).isEqualTo("new@g.com");
        assertThat(m.getNickname()).isEqualTo("Alexander");   // 10자 절단 후 trim
    }

    @Test
    void 같은_provider와_providerId면_동일회원() {
        Member first = oAuthService.upsertOidcUser("GOOGLE", "sub-2", "a@g.com", true, "밥", "http://p");
        Member again = oAuthService.upsertOidcUser("GOOGLE", "sub-2", "changed@g.com", true, "밥", "http://p");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getEmail()).isEqualTo("changed@g.com");   // 검증된 이메일이면 갱신
    }

    @Test
    void providerId가_같아도_provider가_다르면_별개회원() {
        Member google = oAuthService.upsertOidcUser("GOOGLE", "same-id", "g@x.com", true, "구글", null);
        Member kakao = oAuthService.upsertOidcUser("KAKAO", "same-id", null, false, "카카오", null);

        assertThat(kakao.getId()).isNotEqualTo(google.getId());
    }

    @Test
    void 이메일이_없어도_회원이_생성된다() {
        Member m = oAuthService.upsertOidcUser("KAKAO", "kakao-1", null, false, "카카오유저", "http://p");

        assertThat(m.getId()).isNotNull();
        assertThat(m.getEmail()).isNull();
        assertThat(m.getNickname()).isEqualTo("카카오유저");
    }

    @Test
    void 미검증_이메일은_저장하지_않는다() {
        Member m = oAuthService.upsertOidcUser("KAKAO", "kakao-2", "unverified@k.com", false, "카카오", null);

        assertThat(m.getEmail()).isNull();
    }

    @Test
    void 검증된_이메일이_기존회원과_충돌하면_거부() {
        oAuthService.upsertOidcUser("GOOGLE", "sub-3", "dup@g.com", true, "먼저", null);

        assertThatThrownBy(() ->
                oAuthService.upsertOidcUser("KAKAO", "kakao-3", "dup@g.com", true, "나중", null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void 닉네임이_없으면_이메일_로컬파트로_폴백() {
        Member m = oAuthService.upsertOidcUser("GOOGLE", "sub-4", "fallback@g.com", true, null, null);

        assertThat(m.getNickname()).isEqualTo("fallback");
    }

    @Test
    void 닉네임과_이메일이_모두_없으면_user로_폴백() {
        Member m = oAuthService.upsertOidcUser("KAKAO", "kakao-4", null, false, null, null);

        assertThat(m.getNickname()).isEqualTo("user");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*OAuthServiceTest"`
Expected: FAIL — 컴파일 에러(`upsertOidcUser` 메서드 없음).

- [ ] **Step 3: 구현**

`service/OAuthService.java`의 `upsertGoogleUser`와 `toNickname`을 아래로 교체한다(클래스 선언·필드는 유지).

```java
    @Transactional
    public Member upsertOidcUser(String provider, String providerId, String email,
                                 boolean emailVerified, String nickname, String picture) {
        // 1) provider + providerId가 신원. 이메일이 바뀌어도 동일인
        Member existing = memberRepository.findByProviderAndProviderId(provider, providerId).orElse(null);
        if (existing != null) {
            if (emailVerified && email != null && !email.equals(existing.getEmail())
                    && memberRepository.findByEmail(email).isEmpty()) {
                existing.updateEmail(email);
            }
            return existing;
        }

        // 2) 검증된 이메일만 저장한다. 미검증 값이 UNIQUE 슬롯을 선점하지 못하게 한다
        String emailToStore = null;
        if (emailVerified && email != null) {
            if (memberRepository.findByEmail(email).isPresent()) {
                throw new CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            emailToStore = email;
        }

        Member created = Member.ofSocial(provider, providerId, emailToStore, toNickname(nickname, email), picture);
        return memberRepository.save(created);
    }

    private String toNickname(String nickname, String email) {
        String base;
        if (nickname != null && !nickname.isBlank()) {
            base = nickname.trim();
        } else if (email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@'));
        } else {
            base = "user";
        }
        String cut = base.length() > 10 ? base.substring(0, 10) : base;
        String trimmed = cut.trim();
        return trimmed.isEmpty() ? "user" : trimmed;   // nickname 컬럼은 10자 제한
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*OAuthServiceTest"`
Expected: PASS (8개 테스트). 이 시점에도 `CustomOidcUserService`·`OAuth2SuccessHandler`가 옛 시그니처를 참조해 **전체 컴파일은 실패**할 수 있다. Task 4에서 해소된다. 실패하면 Task 4까지 진행 후 재실행한다.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/OAuthService.java \
        src/test/java/com/example/springboot_realtimechat/oauth/OAuthServiceTest.java
git commit -m "feat(oauth): upsertOidcUser로 provider 중립화 + 검증된 이메일만 저장"
```

---

## Task 3: Principal을 member id로 전환

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/CustomUserDetails.java:33-35`
- Modify: `src/main/java/com/example/springboot_realtimechat/redis/RedisSubscriber.java:45-47`

**Interfaces:**
- Produces: `CustomUserDetails.getUsername()`이 member id 문자열을 반환한다. STOMP Principal 이름이 이메일에서 member id로 바뀐다.
- 프론트는 변경하지 않는다. 클라이언트는 `/user/queue/unread`를 구독하고 서버가 Principal로 목적지를 해석한다.

- [ ] **Step 1: CustomUserDetails 수정**

`security/CustomUserDetails.java`의 `getUsername()`을 교체한다. `email` 필드와 `getEmail()`(Lombok `@Getter`)은 그대로 둔다 — 다른 소비자가 참조하며, 이제 null일 수 있다.

```java
    @Override
    public String getUsername() {
        // 식별자는 불변인 member id. 이메일은 바뀔 수 있고 없을 수도 있다
        return String.valueOf(memberId);
    }
```

- [ ] **Step 2: RedisSubscriber 개인 큐 대상 수정**

`redis/RedisSubscriber.java`의 안읽음 fan-out 루프 내부를 교체한다.

변경 전:

```java
                    try {
                        messagingTemplate.convertAndSendToUser(member.getEmail(), "/queue/unread", event);
                    } catch (Exception e) {
                        log.warn("안읽음 전송 실패 (memberId={}, email={})", member.getId(), member.getEmail(), e);
                    }
```

변경 후:

```java
                    try {
                        messagingTemplate.convertAndSendToUser(String.valueOf(member.getId()), "/queue/unread", event);
                    } catch (Exception e) {
                        log.warn("안읽음 전송 실패 (memberId={})", member.getId(), e);
                    }
```

- [ ] **Step 3: 잔여 참조 확인**

Run:
```bash
grep -rn "convertAndSendToUser\|getUsername()" src/main/java
```
Expected: `convertAndSendToUser`는 `RedisSubscriber` 한 곳뿐이고 인자가 `String.valueOf(member.getId())`. `getUsername()` 재정의는 `CustomUserDetails` 한 곳뿐.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/CustomUserDetails.java \
        src/main/java/com/example/springboot_realtimechat/redis/RedisSubscriber.java
git commit -m "refactor(auth): STOMP Principal 식별자를 member id로 전환"
```

---

## Task 4: OIDC 배선에서 provider 판별

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/CustomOidcUserService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java`

**Interfaces:**
- Consumes: `OAuthService.upsertOidcUser(...)` (Task 2), `MemberRepository.findByProviderAndProviderId(...)` (Task 1)
- provider 문자열 규약: `registrationId`(`google`, `kakao`)를 **대문자로 변환**해 `GOOGLE`, `KAKAO`로 저장한다. 기존 데이터(`provider = 'GOOGLE'`)와 일치한다.

- [ ] **Step 1: CustomOidcUserService 수정**

`security/CustomOidcUserService.java`의 `loadUser` 본문을 교체한다.

```java
    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String provider = userRequest.getClientRegistration().getRegistrationId().toUpperCase();
        String providerId = oidcUser.getSubject();
        String email = oidcUser.getEmail();
        boolean emailVerified = Boolean.TRUE.equals(oidcUser.getEmailVerified());

        // 카카오는 표준 name 클레임을 주지 않고 nickname을 준다
        String nickname = oidcUser.getClaimAsString("nickname");
        if (nickname == null || nickname.isBlank()) {
            nickname = oidcUser.getFullName();
        }
        String picture = oidcUser.getPicture();

        try {
            oAuthService.upsertOidcUser(provider, providerId, email, emailVerified, nickname, picture);
        } catch (CustomException e) {
            // 실패 핸들러가 #oauth_error=<코드>로 안내
            throw new OAuth2AuthenticationException(new OAuth2Error(e.getErrorCode().name()), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth_failed"), e.getMessage(), e);
        }
        return oidcUser;
    }
```

- [ ] **Step 2: OAuth2SuccessHandler 수정**

`security/OAuth2SuccessHandler.java`의 `onAuthenticationSuccess` 본문을 교체하고, 상단에 import를 추가한다.

추가할 import:

```java
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
```

본문:

```java
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
        String provider = authToken.getAuthorizedClientRegistrationId().toUpperCase();
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        var member = memberRepository.findByProviderAndProviderId(provider, oidcUser.getSubject())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        response.sendRedirect(frontendUrl + "/#token=" + token);
    }
```

- [ ] **Step 3: 전체 컴파일·테스트 통과 확인**

Run: `./gradlew test`
Expected: **BUILD SUCCESSFUL**. Task 1~3에서 남아 있던 옛 시그니처 참조가 모두 해소된다.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/security/CustomOidcUserService.java \
        src/main/java/com/example/springboot_realtimechat/security/OAuth2SuccessHandler.java
git commit -m "feat(oauth): OIDC 배선에서 registrationId로 provider 판별"
```

---

## Task 5: 카카오 설정 + 부팅·마이그레이션 검증

**Files:**
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/resources/application-local.example.yaml`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces: OAuth 진입 경로 `/oauth2/authorization/kakao`, 콜백 `/login/oauth2/code/kakao`
- 환경변수: `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`

- [ ] **Step 1: application.yaml에 카카오 registration/provider 추가**

`src/main/resources/application.yaml`의 `spring.security.oauth2.client` 블록을 아래로 교체한다. 기존 `google` 항목은 그대로 두고 `kakao`와 `provider` 블록을 추가한다.

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
          kakao:
            client-id: ${KAKAO_CLIENT_ID:dummy-client-id}
            client-secret: ${KAKAO_CLIENT_SECRET:dummy-client-secret}
            client-name: Kakao
            # redirect-uri 기본 템플릿은 CommonOAuth2Provider(google 등)에만 적용된다.
            # 카카오는 커스텀 provider라 비워두면 ClientRegistration 생성 시점에 예외가 나 컨텍스트가 뜨지 않는다
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            authorization-grant-type: authorization_code
            client-authentication-method: client_secret_post
            # account_email은 비즈니스 인증을 받은 앱에만 열리는 동의항목이다.
            # 설정하지 않은 동의항목을 요청하면 카카오가 인가 요청을 KOE205로 거부한다
            scope:
              - openid
              - profile_nickname
              - profile_image
        provider:
          kakao:
            # issuer-uri는 부팅 시 discovery를 네트워크로 가져와 CI/테스트 컨텍스트 로드를 막는다. 엔드포인트를 명시한다.
            # 값은 https://kauth.kakao.com/.well-known/openid-configuration 기준
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            # OIDC 표준 UserInfo. /v2/user/me는 카카오 REST API 응답 형식이라 sub 클레임이 없다
            user-info-uri: https://kapi.kakao.com/v1/oidc/userinfo
            jwk-set-uri: https://kauth.kakao.com/.well-known/jwks.json
            user-name-attribute: sub
```

- [ ] **Step 2: application-local.example.yaml에 예시 추가**

`src/main/resources/application-local.example.yaml`의 `spring.security.oauth2.client.registration` 아래에 kakao 예시를 추가한다.

```yaml
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: your-google-client-id
            client-secret: your-google-client-secret
            scope: [openid, email, profile]
          kakao:
            client-id: your-kakao-rest-api-key
            client-secret: your-kakao-client-secret
```

- [ ] **Step 3: fresh DB 부팅으로 V1~V5 + 카카오 설정 로드 검증**

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS kakao_mig; CREATE DATABASE kakao_mig CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

JWT_SECRET=local-plan-secret-0123456789abcdef0123456789abcdef \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/kakao_mig' \
./gradlew bootRun > /tmp/kakao-boot.log 2>&1 &
```

부팅 후 확인:

```bash
sleep 25
grep -E "Successfully applied 5 migrations|Started SpringbootRealtimechatApplication" /tmp/kakao-boot.log
curl -s -i "http://localhost:8080/oauth2/authorization/kakao" | tr -d '\r' | grep -i "^location:"
```

Expected:
- 로그에 `Successfully applied 5 migrations` + `Started SpringbootRealtimechatApplication`
- `Location` 헤더가 `https://kauth.kakao.com/oauth/authorize?...` 로 시작하고 `redirect_uri=http://localhost:8080/login/oauth2/code/kakao`, `scope=openid ...`를 포함

스키마 확인:

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "SHOW COLUMNS FROM members LIKE 'provider_id'; SHOW COLUMNS FROM members LIKE 'email';" kakao_mig
```

Expected: `provider_id` 존재, `email`의 `Null` 컬럼이 `YES`.

확인 후 서버 종료:

```bash
pkill -f "bootRun" || true
```

- [ ] **Step 4: 기존 구글 회원 이관 검증**

V4 시드에 이어 구글 회원이 있는 상태를 만들어 이관을 확인한다.

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "SELECT id, email, provider, provider_id FROM members;" kakao_mig
```

Expected: 시드된 demo/guest 2행이 `provider='LOCAL'`, `provider_id=NULL`로 존재. (구글 회원은 이 fresh DB에 없다. 프로덕션 이관 검증은 Task 7 배포 체크리스트에서 다룬다.)

- [ ] **Step 5: docker-compose에 KAKAO_* 전달 추가**

`docker-compose.yml`의 `app.environment`에 2줄 추가한다.

```yaml
      GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID}         # OAuth: Google 클라이언트 자격증명 (값은 .env)
      GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET}
      KAKAO_CLIENT_ID: ${KAKAO_CLIENT_ID}           # OAuth: Kakao REST API 키 (값은 .env)
      KAKAO_CLIENT_SECRET: ${KAKAO_CLIENT_SECRET}
      FRONTEND_URL: ${FRONTEND_URL}                 # OAuth 성공 후 JWT 핸드오프 대상 프론트 주소
```

- [ ] **Step 6: compose 렌더 확인**

Run:
```bash
SPRING_DATASOURCE_URL=x SPRING_DATASOURCE_USERNAME=x SPRING_DATASOURCE_PASSWORD=x \
AWS_ACCESS_KEY_ID=x AWS_SECRET_ACCESS_KEY=x JWT_SECRET=x \
GOOGLE_CLIENT_ID=g GOOGLE_CLIENT_SECRET=gs KAKAO_CLIENT_ID=k KAKAO_CLIENT_SECRET=ks \
FRONTEND_URL=https://sagertc.duckdns.org \
docker compose config | grep -iE "KAKAO_CLIENT_ID|KAKAO_CLIENT_SECRET"
```
Expected: `KAKAO_CLIENT_ID: k`, `KAKAO_CLIENT_SECRET: ks`

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yaml \
        src/main/resources/application-local.example.yaml \
        docker-compose.yml
git commit -m "feat(oauth): 카카오 OIDC 클라이언트 설정 + 컨테이너 env 전달"
```

---

## Task 6: 프론트 카카오 로그인 버튼

**Files:**
- Modify: `frontend/src/components/Welcome.tsx`

**Interfaces:**
- Consumes: `OAUTH_BASE` 상수(기존), 소셜 버튼 컨테이너(`flex flex-col gap-3`, 기존)
- Produces: 카카오 버튼 → `${OAUTH_BASE}/oauth2/authorization/kakao`

- [ ] **Step 1: 카카오 로그인 핸들러 추가**

`frontend/src/components/Welcome.tsx`의 `handleGoogleLogin` 바로 아래에 추가한다.

```tsx
  const handleKakaoLogin = () => {
    window.location.href = `${OAUTH_BASE}/oauth2/authorization/kakao`;
  };
```

- [ ] **Step 2: 소셜 버튼 컨테이너에 카카오 버튼 추가**

기존 Google 버튼 `</button>` 바로 다음, 같은 `flex flex-col gap-3` 컨테이너 안에 추가한다. 카카오 공식 가이드(배경 `#FEE500`, 심볼·라벨 `rgba(0,0,0,0.85)`)를 따르고 크기·radius는 Google 버튼과 통일한다.

```tsx
              <button type="button" onClick={handleKakaoLogin} className="sage-cta"
                style={{ width: '100%', background: '#FEE500', color: 'rgba(0,0,0,0.85)', borderRadius: 13, padding: 13, fontWeight: 600, fontSize: 14, border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
                <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                  <path fill="rgba(0,0,0,0.85)" d="M9 1.8c-4.03 0-7.3 2.57-7.3 5.74 0 2.02 1.33 3.79 3.34 4.81l-.85 3.1c-.07.26.22.47.45.32l3.7-2.44c.22.02.44.03.66.03 4.03 0 7.3-2.57 7.3-5.74S13.03 1.8 9 1.8z"/>
                </svg>
                카카오 로그인
              </button>
```

- [ ] **Step 3: 검증**

Run: `cd frontend && npm run lint && npm run build`
Expected: tsc 에러 0, `vite build` 성공.

- [ ] **Step 4: 렌더 확인**

프론트 dev 서버를 띄우고 로그인 카드에 버튼 2개가 있는지 DOM으로 확인한다.

```bash
cd frontend && npm run dev
```

브라우저 콘솔에서:

```js
Array.from(document.querySelectorAll('button')).map(b => b.innerText.trim())
```

Expected: `["아래로 스크롤", "Google로 로그인", "카카오 로그인"]`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/Welcome.tsx
git commit -m "feat(oauth): Welcome에 카카오 로그인 버튼 추가"
```

---

## Task 7: 통합 검증 + 배포 준비

**Files:** 없음 (검증·문서 단계)

- [ ] **Step 1: 전체 자동 검증**

Run:
```bash
./gradlew test
cd frontend && npm run lint && npm run build
```
Expected: 백엔드 BUILD SUCCESSFUL, 프론트 tsc 0 에러 + build 성공.

- [ ] **Step 2: 로컬 E2E — 구글 로그인 회귀**

fresh DB(`kakao_mig`)로 백엔드를 띄우고 프론트 dev를 실행한 뒤 브라우저에서 "Google로 로그인"으로 실제 로그인한다.

Expected: 채널 목록(공지·잡담)이 보이고 자동 로그인된다. DB 확인:

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "SELECT id, email, provider, provider_id, nickname FROM members WHERE provider='GOOGLE';" kakao_mig
```

Expected: 구글 회원 1행, `provider_id`가 채워져 있다.

- [ ] **Step 3: 로컬 E2E — 카카오 로그인**

같은 화면에서 "카카오 로그인"으로 실제 카카오 계정 로그인.

Expected: 자동 로그인되어 채널 목록이 보인다. DB 확인:

```bash
mysql -h 127.0.0.1 -uroot -p1111 -e "SELECT id, email, provider, provider_id, nickname FROM members WHERE provider='KAKAO';" kakao_mig
```

Expected: 카카오 회원 1행 생성. 이메일 미동의/미검증이면 `email`이 `NULL`.

- [ ] **Step 4: 로컬 E2E — 안읽음 개인 큐 회귀 (Principal 변경 검증)**

Task 3에서 Principal을 바꿨으므로 **이 검증이 필수**다. 브라우저 탭 2개로 서로 다른 계정(구글·카카오)에 로그인한 뒤:

1. A 계정으로 "잡담" 채널에 입장해 메시지를 보낸다.
2. B 계정은 채널 랜딩 화면에 머문다.

Expected: B 화면의 "잡담" 우표 안읽음 배지가 **실시간으로 증가**한다. 증가하지 않으면 `convertAndSendToUser` 대상과 Principal 이름이 어긋난 것이므로 Task 3을 재점검한다.

- [ ] **Step 5: 배포 체크리스트 확인 (실행은 사용자)**

다음 항목을 PR 본문 "배포 영향"에 명시한다.

- EC2 `.env`에 `KAKAO_CLIENT_ID`(REST API 키), `KAKAO_CLIENT_SECRET` 추가
- `docker-compose.yml` 전달은 Task 5에서 반영됨
- Flyway V5는 배포 시 자동 적용. **프로덕션의 기존 구글 회원은 `google_sub` → `provider_id`로 이관된다**
- 카카오 콘솔: 카카오 로그인 ON, OIDC ON, Redirect URI(로컬·운영) 등록, 클라이언트 시크릿 발급 — 완료됨
- 실 카카오 로그인 왕복은 배포 후 라이브에서 확인(자동화 미검증 구간)

- [ ] **Step 6: PR 생성**

`.github/pull_request_template.md`의 5개 섹션(개요/변경 내용/검증/배포 영향/구현 노트·알려진 한계)을 그대로 채운다. 스키마·설정 변경이 있으므로 `## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가해 V5 마이그레이션과 Principal 전환을 명시한다. `## 검증`에는 실제로 실행한 것만 쓴다.

```bash
git push -u origin feat/kakao-login
gh pr create --base develop --title "feat(oauth): 카카오 로그인 추가 + 신원 모델 provider 일반화"
```

---

## Self-Review 결과

**Spec coverage**: §2 신원 모델→T1, §3 Principal 전환→T3, §4 OAuthService 일반화·이메일 저장 규칙·닉네임 폴백→T2(+T4의 nickname 클레임), §5 D1/D2→T2 테스트로 고정, §6 카카오 설정→T5, §7 프론트 버튼→T6, §8 V5 마이그레이션→T1 Step 1·T5 Step 3, §9 검증→각 태스크 + T7, §10 배포 영향(env 양쪽)→T5 Step 5·T7 Step 5. 커버 확인.

**타입 일관성**: `upsertOidcUser(String, String, String, boolean, String, String)` 시그니처가 T2 정의·T4 호출부에서 동일. `findByProviderAndProviderId(String, String)`가 T1 정의·T2·T4 사용처에서 동일. `Member.ofSocial(provider, providerId, email, nickname, profileImageUrl)` 인자 순서가 T1 정의·T2 호출·T1 테스트에서 동일. provider 값은 전 구간 대문자(`GOOGLE`/`KAKAO`).

**빌드 순서 주의**: T1~T3 중간에는 옛 시그니처 참조로 전체 컴파일이 실패할 수 있다. 각 태스크에 명시했고 T4 Step 3에서 전체 그린을 확인한다.
