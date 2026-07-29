# 카카오 소셜 로그인 설계 (신원 모델 일반화)

- 작성일: 2026-07-29
- 범위: 카카오 로그인 추가 + 신원 모델을 provider 중립으로 일반화 + 이메일을 선택 속성으로 전환

## 1. 배경과 목표

로그인은 현재 소셜 전용(구글)이다. 여기에 **카카오 로그인**을 추가한다.

그런데 현재 스키마·인증 경로가 구글과 이메일에 묶여 있다.

| 항목 | 현재 | 문제 |
|---|---|---|
| 신원 컬럼 | `google_sub` (UNIQUE) | 구글 전용. 카카오 신원을 담을 자리가 없다 |
| `members.email` | `NOT NULL` + UNIQUE | 카카오는 이메일을 주지 않을 수 있다(선택 동의) |
| STOMP Principal | `CustomUserDetails.getUsername()` = **email** | 이메일은 변하는 값이다(`Member.updateEmail`이 실제로 호출됨). 변하는 값을 식별자로 쓰면 이메일 변경 시 개인 큐(`/queue/unread`)가 끊긴다 |

따라서 이 작업의 목표는 두 가지다.

1. 카카오 로그인 추가
2. **신원을 이메일에서 분리** — 식별자는 불변의 내부 id와 `(provider, provider_id)`, 이메일은 선택 속성

두 번째는 카카오를 위한 우회가 아니라 기존 설계 결함의 교정이다. 성숙한 인증 시스템(Firebase Auth, Auth0, Supabase 등)이 이메일을 선택 속성으로 두는 것과 같은 방향이다.

**대안으로 검토했다가 버린 것**: 이메일이 없는 카카오 유저에게 `kakao_<id>@…` 형태의 가짜 이메일을 채우는 방식. 스키마를 안 건드려도 되지만, 프로필에 가짜 주소가 노출되고 "이메일 없음"과 "있음"을 구분할 수 없게 되며 향후 메일 기능이 조용히 실패한다. 위 2번을 하면 이 우회가 불필요하다.

## 2. 신원 모델

```
변경 전:  google_sub VARCHAR(255) UNIQUE       , email NOT NULL UNIQUE
변경 후:  provider + provider_id 복합 UNIQUE   , email NULL 허용 UNIQUE
```

- `provider`는 이미 존재(`LOCAL` / `GOOGLE`)한다. 여기에 `KAKAO`가 추가된다.
- `provider_id` = 소셜 제공자의 안정적 고유 ID(구글·카카오 모두 OIDC `sub`).
- 기존 `google_sub` 값은 `provider_id`로 이관하고 컬럼을 제거한다.
- `UNIQUE (provider, provider_id)` — LOCAL 계정은 `provider_id`가 NULL이며, MySQL은 유니크 인덱스에서 NULL 중복을 허용하므로 시드된 demo/guest 두 행이 공존한다.
- `email`은 NULL 허용 + UNIQUE 유지. 마찬가지로 이메일 없는 카카오 유저가 여럿이어도 충돌하지 않는다.

## 3. Principal 전환 (이메일 → member id)

| 파일 | 변경 |
|---|---|
| `security/CustomUserDetails.java` | `getUsername()`이 `email` 대신 `String.valueOf(memberId)` 반환 |
| `redis/RedisSubscriber.java` | `convertAndSendToUser(member.getEmail(), "/queue/unread", …)` → member id 문자열 |
| 프론트 | **변경 없음.** 클라이언트는 `/user/queue/unread`를 구독하고 서버가 Principal로 해석한다 |

`CustomUserDetails`의 `email` 필드 자체는 유지한다(다른 소비자가 참조). 다만 null일 수 있다.

**회귀 위험 지점은 안읽음 개인 큐 한 곳**이다. 검증 계획(§8)에서 E2E로 확인한다.

## 4. OAuth 서비스 일반화

`OAuthService.upsertGoogleUser(sub, email, emailVerified, name, picture)`
→ **`upsertOidcUser(provider, providerId, email, emailVerified, nickname, picture)`**

매칭 순서:

1. **`(provider, providerId)`로 조회** → 있으면 그 회원. 검증된 이메일이 새로 들어왔고 값이 다르면 갱신한다. 단, 그 이메일을 이미 다른 회원이 보유하고 있으면 갱신은 건너뛰고 기존 이메일을 유지한 채 로그인을 진행한다(§5 D1).
2. 없으면 **신규 생성**. 이때 이메일은 아래 규칙을 따른다.

**이메일 저장 규칙(신규 생성)** — 검증된 이메일만 저장한다.

| 들어온 이메일 | 처리 |
|---|---|
| 검증됨(`email_verified=true`) + 기존 회원 없음 | 그 이메일로 저장 |
| 검증됨 + **같은 이메일의 기존 회원 있음** | **거부** — `CustomException(EMAIL_ALREADY_REGISTERED)` (§5 D1) |
| 미검증 또는 미제공 | `email = null`로 생성 |

미검증 이메일을 저장하지 않는 이유: `email`은 UNIQUE라 한번 저장되면 그 주소를 선점한다. 검증되지 않은 값이 남의 이메일을 선점하면 D1이 막으려는 계정 선점이 그대로 재현된다. 저장하지 않으면 충돌 자체가 생기지 않는다.

이 표는 **신규 생성** 경로에만 적용된다. 1단계에서 `(provider, providerId)`로 이미 매칭된 **기존 회원**은 다르게 다룬다 — 새 이메일이 다른 회원과 충돌해도 거부하지 않고 갱신만 건너뛴다(§5 D1). 이미 신원이 확인된 사용자를 자신이 통제하지 못하는 이메일 값 때문에 계정에서 잠그지 않기 위함이다.

실질적 결과: 카카오가 `email_verified`를 주지 않으면 카카오 계정은 대체로 `email = null`로 생성된다. 이는 의도된 안전한 기본값이며, §2에서 이메일을 선택 속성으로 만들었기에 아무 문제가 없다.

`CustomOidcUserService`는 `userRequest.getClientRegistration().getRegistrationId()`로 `google` / `kakao`를 판별해 `provider`를 넘긴다. 카카오도 OIDC이므로 **같은 `OidcUserService` 경로를 그대로 탄다**.

### 구현 시 주의

- **닉네임 클레임**: `OidcUser.getFullName()`은 `name` 클레임을 읽는데 카카오는 이를 제공하지 않는다. 카카오는 `nickname` 클레임을 준다. 따라서 `nickname` → `name` → 이메일 로컬파트 → `"user"` 순으로 폴백한다.
- `Member.ofGoogle` / `linkGoogle`은 provider 중립 이름으로 정리한다(`ofSocial` 등).

## 5. 결정 사항

**D1. 같은 이메일이 다른 소셜로 들어오면 → 신규 생성은 거부한다.**
자동 연결하지 않는다. 카카오 이메일의 검증 신뢰도가 불명확하고, 자동 연결은 계정 선점(pre-account-hijacking) 경로가 된다. 사용자에게는 기존 소셜로 로그인하도록 안내한다(프론트 문구는 이미 이 방향으로 반영됨). `email` UNIQUE 제약이 이 정책을 DB 레벨에서도 보장한다.

단, 이미 `(provider, providerId)`로 신원이 확인된 **기존 회원**의 로그인 시점 이메일 갱신은 이 거부 대상이 아니다. 새 이메일이 다른 회원 소유와 충돌하면 갱신만 건너뛰고 기존 이메일을 유지한 채 로그인을 허용한다 — 이미 인증된 사용자를 자신이 통제할 수 없는 이메일 값 때문에 자기 계정에서 잠그지 않기 위함이다.

**D2. 카카오 회원은 이메일 없이 생성한다.**
`account_email`은 **비즈니스 인증을 받은 앱에만 열리는 동의항목**이다. 개인 개발자 앱에서는 콘솔에 "권한 없음"으로 표시되어 활성화할 수 없고, 설정하지 않은 동의항목을 요청하면 카카오가 인가 요청 자체를 `KOE205`로 거부한다. 따라서 scope에서 제외하고, 카카오 회원은 `email = null`로 만든다.

신원은 `(provider, provider_id)`이므로 이메일이 없어도 로그인·재로그인 매칭에 영향이 없다. 저장 규칙 자체는 §4 그대로다 — 검증된 이메일만 저장하고, 없으면 `null`. 구글은 종전대로 이메일을 받는다.

향후 비즈니스 인증을 거쳐 `account_email`이 열리면 scope에 추가하는 것만으로 동작한다.

## 6. 카카오 설정

`application.yaml`:

- `registration.kakao`: `client-id`(REST API 키), `client-secret`, `scope: [openid, profile_nickname, profile_image]`(§5 D2), `authorization-grant-type: authorization_code`, `client-authentication-method: client_secret_post`, `redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"`
  - `redirect-uri`를 명시하는 이유: 기본 템플릿은 `CommonOAuth2Provider`(google 등)에만 적용된다. 카카오는 커스텀 provider라 값이 비면 `ClientRegistration` 생성 시점에 예외가 나 애플리케이션 컨텍스트가 뜨지 않는다
- `provider.kakao`: `authorization-uri`, `token-uri`, `user-info-uri`, `jwk-set-uri`, `user-name-attribute: sub`를 명시한다. `issuer-uri`는 쓰지 않는다 — `issuer-uri`를 쓰면 Spring이 부팅 시 OIDC discovery 문서를 네트워크로 가져오는데, 이는 CI와 오프라인 테스트 환경의 컨텍스트 로딩을 막는다. 엔드포인트를 명시하면 네트워크 호출 없이 부팅된다
- 구글과 동일하게 더미 기본값(`${KAKAO_CLIENT_ID:dummy-client-id}`)을 두어 로컬/테스트 컨텍스트가 로드되게 한다

**구현 시 확인**: 카카오 토큰 엔드포인트는 client secret을 POST 파라미터로 받는다. Spring 기본값(`client_secret_basic`)으로 실패하면 `client-authentication-method: client_secret_post`를 지정한다.

콘솔 설정은 완료됨: 카카오 로그인 활성화, OpenID Connect 활성화, Redirect URI 2개(`/login/oauth2/code/kakao` — 로컬·운영), 클라이언트 시크릿 발급.

## 7. 프론트

`Welcome.tsx`의 소셜 버튼 컨테이너(이미 확보된 자리)에 카카오 버튼을 추가한다. 카카오 공식 가이드를 따른다.

- 배경 `#FEE500`, 심볼·라벨 `rgba(0,0,0,0.85)`, 라벨 "카카오 로그인"
- 말풍선 아이콘은 인라인 SVG(구글 버튼과 동일 방식)
- 크기·패딩·radius(13)는 구글 버튼과 통일, 세로 배치
- 클릭 시 `${OAUTH_BASE}/oauth2/authorization/kakao`

## 8. 마이그레이션 (V5)

Flyway로 배포 시 자동 적용된다. 수동 ALTER 없음.

```sql
ALTER TABLE members MODIFY email VARCHAR(255) NULL;
ALTER TABLE members ADD COLUMN provider_id VARCHAR(255) NULL;
UPDATE members SET provider_id = google_sub WHERE google_sub IS NOT NULL;
ALTER TABLE members DROP INDEX uk_members_google_sub;
ALTER TABLE members DROP COLUMN google_sub;
ALTER TABLE members ADD CONSTRAINT uk_members_provider UNIQUE (provider, provider_id);
```

**기존 구글 계정 보존이 핵심이다.** 프로덕션에는 이미 구글로 로그인한 실제 계정이 있다. `UPDATE`가 `google_sub`를 `provider_id`로 옮기므로, 배포 후에도 같은 구글 계정으로 로그인하면 동일 회원에 매칭된다.

## 9. 검증

- **유닛 테스트**(`OAuthServiceTest` 확장)
  - `(provider, providerId)` 매칭으로 기존 회원 반환
  - 같은 `providerId`라도 provider가 다르면 별개 회원
  - 이메일 없는 소셜 유저 생성 성공
  - **미검증 이메일은 저장하지 않고 `null`로 생성**
  - 검증된 이메일이 기존 회원과 충돌하면 `EMAIL_ALREADY_REGISTERED`
  - 닉네임 폴백(nickname → name → 이메일 로컬파트 → "user")
- **마이그레이션**: fresh DB에 V1~V5 적용 + `google_sub`가 있는 행을 넣은 DB에서 `provider_id`로 이관되는지 확인
- **E2E(로컬)**
  - 구글 로그인 — 기존 계정에 그대로 매칭(새 계정이 생기지 않아야 함)
  - 카카오 로그인 — 신규 회원 생성, 이메일 미동의 시 `email IS NULL`로 생성
  - **안읽음 개인 큐** — Principal 변경의 회귀 검증. 다른 방에 메시지 도착 시 배지가 실시간 증가하는지
- 백엔드 `./gradlew test`, 프론트 `npm run lint && npm run build`

## 10. 배포 영향

- **스키마 변경 있음** — Flyway V5, 배포 시 자동 적용
- **환경변수 추가**: `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`
  → EC2 `.env`(값) + `docker-compose.yml`의 `app.environment`(전달) **양쪽**에 넣어야 한다
- 배포 직후 기존 발급 JWT를 가진 세션은 Principal이 id로 바뀐다. 재연결 시 정상화되며 데이터 영향은 없다

## 11. 범위 밖

- 한 회원이 여러 소셜을 동시에 연결하는 기능(별도 `user_identities` 테이블). 지금은 회원당 provider 하나다
- 로그인 후 설정 화면에서 소셜 계정 연결/해제
- 이메일 인증(확인 링크) — 공개 이메일 가입을 닫았으므로 현재 불필요
- 카카오 비즈니스 인증(이메일 필수 동의를 받기 위한 전환)
