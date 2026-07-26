# Google OAuth 로그인 (이메일/비번과 공존) — 설계

- 날짜: 2026-07-25
- 브랜치: `feat/oauth-google` (develop 분기)
- 범위: **Google 소셜 로그인**을 기존 이메일/비밀번호 로그인과 **공존**시킨다. 이 시점에 RDS 데이터 전체 초기화 + 데모 계정 시드도 함께 한다.
- 제외: Kakao/GitHub(추후 확장), 이메일 인증(로컬 가입 메일 소유 검증), 별도 `user_identities` 테이블(다중 소셜 연결, Level 2).

## 목표

사용자가 "Google로 로그인" 한 번으로 가입·로그인되게 하되, 이메일/비밀번호 로그인도 그대로 유지한다. 리뷰어·채용담당자가 **소셜 계정 없이도** 데모 계정(이메일/비번)으로 바로 체험할 수 있어야 한다.

## 핵심 결정 (brainstorming 확정)

1. **공존**: 소셜 + 이메일/비번 둘 다. `password`는 nullable.
2. **Google부터** 하나로 시작(내장 `CommonOAuth2Provider`), 나머지는 확장.
3. **신원 = Google `sub` (Level 1)**: 현업 정석의 핵심("이메일이 아니라 OIDC `sub`가 신원")을 담되, 단일 `members` 테이블은 유지. 매칭은 **sub 우선 → 검증된 이메일 연결 → 생성** 순.
4. **전체 초기화 + 데모 시드**(Flyway V4), 초기화는 RDS drop+recreate.
5. **JWT 핸드오프 = URL 프래그먼트**(`/#token=`), 기존 localStorage+Bearer+STOMP 모델 유지.

> **Level 1을 고른 이유**: 프로덕션은 신원을 `(provider, sub)`로 잡고 보통 별도 `user_identities` 테이블(1:N)로 다중 연결을 지원한다(Level 2). 여기선 제공자가 Google 하나뿐이라 별도 테이블은 YAGNI. 대신 "sub가 신원"이라는 핵심만 단일 테이블에 담는다.

## 1. 데이터 모델 (`members`)

기존 컬럼(`id`, `email`(unique), `nickname`, `profile_image_url`, `created_at`)에 더해:

- `password` → **nullable**로 변경. (소셜 전용 계정은 비번 없음.)
- `provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL'` — **최초 가입 경로**(LOCAL/GOOGLE). 표시·분석용, 생성 시 확정되고 이후 불변.
- `google_sub VARCHAR(255) NULL` + **UNIQUE 인덱스** — Google의 안정적 식별자(`sub`). **구글 로그인 가능 여부 + 매칭 키**. null이면 구글로 로그인할 수 없는 계정.

**로그인 능력은 컬럼으로 표현된다**: `password != null` → 비번 로그인 가능, `google_sub != null` → 구글 로그인 가능, 둘 다 있으면 연결된 계정. `email`은 여전히 unique이며 사람 눈에 보이는 신원이지만, **구글 매칭의 1차 키는 `google_sub`**다.

- 엔티티: `Member.password`를 nullable로, `provider`·`googleSub` 필드 추가. 소셜 생성용 정적 팩토리(예: `Member.ofGoogle(email, nickname, imageUrl, sub)` → password=null, provider=GOOGLE, googleSub=sub)와 연결용 메서드(`linkGoogle(sub)`).

## 2. 백엔드 흐름

```
프론트 "Google로 로그인"
  → GET /oauth2/authorization/google         (Spring 기본 진입점)
  → Google 동의 화면
  → GET /login/oauth2/code/google            (Spring 기본 콜백)
  → OidcUserService: sub·email·email_verified·name·picture 추출
  → 매칭(아래 순서)
  → OAuth2 성공 핸들러: 기존과 동일한 JWT 발급(memberId, email)
  → 302 redirect → ${FRONTEND_URL}/#token=<JWT>
```

### 2.1 매칭 로직 (Level 1) — sub 우선

```
1) findByGoogleSub(sub)
     있음 → 그 회원 (이메일이 바뀌었어도 sub로 동일인 식별)
2) 없으면 findByEmail(email)
     있음 + email_verified == true
        → 연결: 그 회원에 google_sub = sub 세팅 (이제 구글로도 로그인 가능)
     있음 + email_verified == false
        → 연결 안 함(takeover 방지). ${FRONTEND_URL}/#oauth_error=email_exists 로 안내
     없음
        → 신규 생성: email, password=null, provider=GOOGLE, google_sub=sub,
                     nickname = Google name 앞 10자(없으면 email local-part),
                     profileImageUrl = Google picture
```

- `findByGoogleSub`로 매칭된 회원의 이메일이 Google 이메일과 다르면 **이메일을 최신값으로 동기화**(선택, 권장). 닉네임/사진은 사용자가 앱에서 바꿨을 수 있으니 덮어쓰지 않는다.
- 신규 생성 시 email이 이미 존재하는 경우는 위 2)에서 이미 걸러진다(없을 때만 생성).

### 2.2 구성요소

- 의존성: `spring-boot-starter-oauth2-client`. Google은 내장 → `client-id`/`client-secret`만 설정.
- 신규 클래스(예):
  - `CustomOidcUserService extends OidcUserService` — 위 매칭 수행 후 principal 반환.
  - `OAuth2SuccessHandler` — 회원 조회 → `jwtTokenProvider.createAccessToken(...)` → `${FRONTEND_URL}/#token=` 리다이렉트.
  - `OAuth2FailureHandler`(선택) — 실패/`email_exists` 시 `${FRONTEND_URL}/#oauth_error=...`.
- `SecurityConfig.oauth2Login(...)`: `oidcUserService` 등록, `successHandler`/`failureHandler`, `permitAll`에 `/oauth2/**`·`/login/oauth2/**` 추가.
- **기존 무변경**: JWT 필터, 이메일/비번 로그인, 회원가입, STOMP Bearer. OAuth는 로그인 진입점만 추가.

### 2.3 기술 게이트 — STATELESS + oauth2Login 함정 (중요)

현재 `SessionCreationPolicy.STATELESS`라 OAuth2의 authorization request(및 CSRF용 `state`)를 HttpSession에 담을 수 없다. 기본 저장소를 쓰면 콜백에서 `authorization_request_not_found`로 실패한다.

→ **쿠키 기반 `AuthorizationRequestRepository`**(`HttpCookieOAuth2AuthorizationRequestRepository`, 표준 패턴)를 등록해 authorization request를 짧은 수명 쿠키로 콜백까지 넘긴다. `.oauth2Login().authorizationEndpoint().authorizationRequestRepository(cookieRepo)`. 이 기능에서 가장 흔히 막히는 지점이라 반드시 포함.

## 3. 프론트

- `Welcome.tsx` 로그인 카드에 **"Google로 로그인"** 버튼(로그인·회원가입 모드 둘 다 노출). 클릭 시 **전체 페이지 이동**:
  `window.location.href = \`${OAUTH_BASE}/oauth2/authorization/google\``.
- **`OAUTH_BASE` 구성**(dev/prod 라우팅 — 실질 게이트):
  - **dev**: OAuth 왕복을 백엔드 오리진에서 처리 → `OAUTH_BASE = http://localhost:8080`(vite 프록시 우회). 콜백 redirect_uri = `http://localhost:8080/login/oauth2/code/google`.
  - **prod**: 프론트·백엔드 동일 오리진(nginx) → `OAUTH_BASE = ''`(same-origin), nginx가 `/oauth2/**`·`/login/oauth2/**` 프록시. 콜백 redirect_uri = `https://sagertc.duckdns.org/login/oauth2/code/google`.
  - 프론트 env `VITE_OAUTH_BASE`로 주입(dev=8080, prod=빈값).
- **핸드오프 수신**(라우터 불필요): App 마운트 시 `location.hash`에 `token=`이 있으면 → 파싱 → `getMe(token)` → `persistSession` → `history.replaceState`로 해시 제거 → 채팅 진입. 루트 `/#token=`으로 받으니 새 라우트·nginx SPA 폴백 이슈 없음.
- `#oauth_error=email_exists`가 오면 로그인 화면에 안내("이 이메일은 이미 가입돼 있어요. 이메일/비번으로 로그인해 주세요").

## 4. 데이터 초기화 + 데모 시드

- **V3**(스키마):
  ```sql
  ALTER TABLE members MODIFY password VARCHAR(255) NULL;
  ALTER TABLE members ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
  ALTER TABLE members ADD COLUMN google_sub VARCHAR(255) NULL;
  ALTER TABLE members ADD CONSTRAINT uk_members_google_sub UNIQUE (google_sub);
  ```
- **V4**(데모 시드) — 지금까지 만든 기능이 **로그인 즉시 한 화면에 다 보이게**:
  - 회원: `demo@demo.com`(닉네임 "데모") + `guest@demo.com`(닉네임 "게스트"). 이메일·비번(provider=LOCAL), 비번은 **BCrypt 해시를 SQL에 박음**(구현 시 생성).
  - 방 여러 개에 두 계정이 오간 **대화**(실시간 채팅 데모).
  - **일부 방은 `demo` 입장에서 안 읽은 메시지가 남게** → 랜딩 우표에 **안읽음 소인**(1·2자리 등 다양)까지 노출. 나머지는 다 읽은 상태(소인 없는 깨끗한 우표) → 두 상태 대비까지 보임.
  - 안읽음은 `chatroom_members.last_read_message_id`를 방별로 조절해서 만든다(어떤 방은 최신=0안읽음, 어떤 방은 낮게=N안읽음).
  - 답장 메시지 한두 개 섞어 "여기부터 안 읽음" 구분선·답장 UI까지 데모.
- **초기화**: 배포 전 RDS 스키마 drop+recreate(사장님이 직접, hands-on) → 재배포 시 Flyway가 V1~V4 fresh 적용 → 깨끗한 스키마 + 데모 시드. (V4를 기존 DB에 얹으면 쓰레기와 섞이므로 초기화가 전제.)
- 시드 방법 = **Flyway V4**(CommandLineRunner 아님). 배포 자동 적용 + 재현 가능.

## 5. 배포

- **Google Cloud Console**(사장님 계정): OAuth 2.0 클라이언트 ID 생성.
  - 승인된 리다이렉트 URI: `http://localhost:8080/login/oauth2/code/google`(로컬) + `https://sagertc.duckdns.org/login/oauth2/code/google`(운영).
  - 동의화면 scope: `openid`, `email`, `profile`.
- **env 추가**(EC2 `.env`): `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `FRONTEND_URL`(=`https://sagertc.duckdns.org`).
  - `application.yaml`: `spring.security.oauth2.client.registration.google.client-id: ${GOOGLE_CLIENT_ID}` 등. 로컬은 `application-local`.
- **nginx**: `/oauth2/`·`/login/oauth2/` location을 백엔드로 프록시 추가(현재 `/api`·`/ws`만). 빠지면 콜백 404.

## 6. 검증

- **백엔드 단위 테스트**(`@SpringBootTest`, H2):
  - **sub 매칭**: 같은 `sub`이면 이메일이 달라도 동일 회원 반환.
  - **검증된 이메일 연결**: 기존 LOCAL 회원 + `email_verified=true` → 그 회원에 `google_sub` 세팅(연결), 새 회원 안 만듦.
  - **미검증 이메일 차단**: 기존 회원 + `email_verified=false` → 연결/생성 안 하고 `email_exists`.
  - **신규 생성**: 처음 보는 sub·email → 생성(password null, provider GOOGLE, google_sub 세팅, nickname 10자 절단).
  - **비번 null 계정의 비번 로그인 거부**: `password==null`인 소셜 계정에 이메일/비번 로그인 → 거부(현 `passwordEncoder.matches`에 null 넣으면 예외 → null 가드 필요).
  - JWT 발급이 memberId/email을 담는지.
- **프론트**: 해시 핸드오프를 **모의 토큰**으로 검증(유효 토큰 심어 로드→getMe→세션 저장→해시 제거). `#oauth_error` 경로도. lint+build.
- **제약(정직)**: 실제 Google 로그인 화면 통과는 자동화가 자격증명을 넣을 수 없다(넣어서도 안 됨). → **실 구글 왕복은 사장님이 로컬/배포에서 직접 확인**(Google Console 앱도 사장님이 생성 → 자연스러운 hands-on). 자동화는 "구글 경계 직전"과 "핸드오프 이후"를 검증.
- **Flyway**: 빈 MySQL에 V1~V4 적용 후 `validate` 통과 부팅 + 데모 데이터 존재 확인.

## 7. 엣지케이스 / 알려진 한계

- **자동 연결 takeover**: Level 1에서 `email_verified=true`일 때만 이메일 연결하므로, "남의 gmail로 미리 비번가입 → 진짜 주인 구글 로그인이 그 계정으로" 시나리오는 **거의 닫힘**(구글은 검증된 이메일만 verified로 줌). 로컬 가입 자체의 메일 미검증은 여전히 남는 한계(범위 밖, 이메일 인증 붙이면 완전히 닫힘).
- **비번 없는 소셜 계정 + 비번 로그인**: `password==null` → `matches` 호출 전 null 가드 → "소셜 로그인 계정입니다" 안내.
- **Google name > 10자 / 없음**: nickname length 10 → 앞 10자 절단, 없으면 email local-part 폴백.
- **닉네임 중복**: unique 아님(식별은 email/sub) → 허용, 문제 없음.
- **이메일 변경 후 재로그인**: `google_sub`로 동일인 식별 → 로그인 유지, 이메일 최신값 동기화(선택).
- **로그아웃**: STATELESS JWT → 기존대로 localStorage 비우기. OAuth가 바꾸지 않음(구글 세션 자체는 유지, 앱 로그아웃만).
- **다중 소셜(Google+GitHub 등)**: 단일 테이블·단일 `google_sub`라 지금은 불가. 필요해지면 별도 `user_identities` 테이블(Level 2)로 이관.

## 8. 의존성·브랜치

- 브랜치 `feat/oauth-google` → develop PR.
- 스키마 변경 → **Flyway V3/V4로 자동 적용**(수동 ALTER 금지). 배포 전 RDS 초기화는 사장님이 실행.
- Google Console 앱 생성 + env 3개는 사장님(hands-on). 배포 순서: RDS 초기화 → env 채우기 → nginx 프록시 추가 → 머지/배포.

## 9. 구현 중 보강·정정 (2026-07-26)

- **쿠키 AuthorizationRequestRepository를 HMAC 서명**(§2.3 보강): 최종 보안 리뷰가 CWE-502(클라이언트가 통제하는 쿠키를 raw `ObjectInputStream`으로 역직렬화 — 콜백 permitAll·state 검증 전이라 인증 없이 도달 가능) 지적. → 쿠키 값을 `base64(HMAC-SHA256(body, jwt.secret)) + "." + base64(body)`로 저장하고, 로드 시 `MessageDigest.isEqual`(상수시간)로 서명 검증에 **통과한 뒤에만** `readObject` 실행. 공격자는 secret 없이 유효 서명을 만들 수 없어 서버가 서명한 바이트만 역직렬화됨. 왕복·위조거부·점없음 유닛 테스트 3개.
- **후속 하드닝(이번 범위 밖, 이슈 후보)**: ① `SOCIAL_LOGIN_ONLY` 미사용 상수 제거(비번 null은 `INVALID_PASSWORD`로 — 계정유형 노출 방지 측면에서 이게 더 안전). ② `toNickname` codepoint-safe 절단(이모지 서로게이트 분리 시 utf8mb4 rare 500 방지; 한글 무관). ③ `toNickname` 폴백 분기 테스트. ④ V4 시드 명시 PK 멱등화(현재는 fresh DB 전제 — 배포 체크리스트가 RDS 초기화로 보장, 비어있지 않은 DB엔 PK 충돌로 loud fail). ⑤ `forward-headers-strategy: framework`는 X-Forwarded-* 무조건 신뢰 — 도커 네트워크 격리 + Google redirect_uri allow-list로 실질 완화.
