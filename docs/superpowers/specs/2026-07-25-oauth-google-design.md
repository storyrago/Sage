# Google OAuth 로그인 (이메일/비번과 공존) — 설계

- 날짜: 2026-07-25
- 브랜치: `feat/oauth-google` (develop 분기)
- 범위: **Google 소셜 로그인**을 기존 이메일/비밀번호 로그인과 **공존**시킨다. 이 시점에 RDS 데이터 전체 초기화 + 데모 계정 시드도 함께 한다.
- 제외: Kakao/GitHub(추후 provider 설정 추가로 확장), 이메일 인증(메일 소유 검증), 계정 설정에서의 소셜 연결 해제.

## 목표

사용자가 "Google로 로그인" 한 번으로 가입·로그인되게 하되, 이메일/비밀번호 로그인도 그대로 유지한다. 리뷰어·채용담당자가 **소셜 계정 없이도** 데모 계정(이메일/비번)으로 바로 체험할 수 있어야 한다. 나머지 제공자는 "email이 신원"이라는 전제만 잡아두면 나중에 설정 추가로 붙는다.

## 핵심 결정 (brainstorming 확정)

1. **공존**: 소셜 + 이메일/비번 둘 다. `password`는 nullable.
2. **Google부터** 하나로 시작(내장 `CommonOAuth2Provider`), 나머지는 확장.
3. **자동 연결(by email)**: Google 이메일이 기존 회원과 같으면 그 회원으로 로그인, 없으면 새로 생성.
4. **전체 초기화 + 데모 시드**(Flyway V4), 초기화는 RDS drop+recreate.
5. **JWT 핸드오프 = URL 프래그먼트**(`/#token=`), 기존 localStorage+Bearer+STOMP 모델 유지.

## 1. 데이터 모델 (`members`)

- `password` → **nullable**로 변경. (소셜 사용자는 비번이 없음.)
- `provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL'` 추가 — 가입 경로(`LOCAL`/`GOOGLE`) 기록. **식별·조회는 여전히 email**. provider는 정보/표시용.
- 식별 키 = **email**(기존 `unique` 유지). 자동 연결이 여기서 성립.
- `providerId`(Google `sub`)는 **저장하지 않음**: 자동 연결이 email 기준이라 불필요. (email 변경 대응이 필요해지면 후속으로 추가 가능 — 알려진 한계.)
- 엔티티: `Member.password` 제약을 nullable로, `provider` 필드 추가. 소셜 회원 생성용 정적 팩토리 또는 생성자 추가(`Member.ofGoogle(email, nickname, profileImageUrl)` 등, password=null, provider=GOOGLE).

## 2. 백엔드 흐름

```
프론트 "Google로 로그인"
  → GET /oauth2/authorization/google         (Spring 기본 진입점)
  → Google 동의 화면
  → GET /login/oauth2/code/google            (Spring 기본 콜백)
  → OidcUserService: email·name·picture 추출
  → find-or-create(by email):
        기존 회원 있음 → 그 회원 (자동 연결, provider 변경 없음)
        없음         → 새 회원 생성(password=null, provider=GOOGLE,
                        nickname=Google name 앞 10자, profileImageUrl=Google picture)
  → OAuth2 성공 핸들러: 기존과 동일한 JWT 발급(memberId, email)
  → 302 redirect → ${FRONTEND_URL}/#token=<JWT>
```

- 의존성: `spring-boot-starter-oauth2-client` 추가. Google은 내장 → `client-id`/`client-secret`만 설정.
- 신규 클래스(예):
  - `CustomOidcUserService extends OidcUserService` — Google 유저 로드 시 find-or-create 후 principal 반환.
  - `OAuth2SuccessHandler implements AuthenticationSuccessHandler` — 회원 조회 → `jwtTokenProvider.createAccessToken(...)` → `${FRONTEND_URL}/#token=` 리다이렉트.
  - `OAuth2FailureHandler`(선택) — 실패 시 `${FRONTEND_URL}/#oauth_error=...`로.
- `SecurityConfig`에 `.oauth2Login(...)` 추가:
  - `userInfoEndpoint().oidcUserService(customOidcUserService)`
  - `.successHandler(oAuth2SuccessHandler)` / `.failureHandler(...)`
  - `permitAll`에 `/oauth2/**`, `/login/oauth2/**` 추가.
- **기존은 무변경**: JWT 필터, 이메일/비번 로그인(`/api/auth/login`), 회원가입(`POST /api/members`), STOMP Bearer 인증 그대로. OAuth는 로그인 진입점만 추가한다.

### 2.1 기술 게이트 — STATELESS + oauth2Login 함정 (중요)

현재 `SessionCreationPolicy.STATELESS`라 OAuth2의 authorization request(및 CSRF용 `state`)를 **HttpSession에 저장할 수 없다.** 기본 `HttpSessionOAuth2AuthorizationRequestRepository`를 쓰면 콜백에서 `authorization_request_not_found`로 실패한다.

→ **쿠키 기반 `AuthorizationRequestRepository`**를 구현/등록한다(`HttpCookieOAuth2AuthorizationRequestRepository`, 표준 패턴). authorization request를 짧은 수명의 쿠키에 담아 콜백까지 넘긴다. `SecurityConfig`의 `.oauth2Login().authorizationEndpoint().authorizationRequestRepository(cookieRepo)`로 연결.

이 항목이 이 기능에서 가장 흔히 막히는 지점이라 반드시 포함한다.

## 3. 프론트

- `Welcome.tsx` 로그인 카드에 **"Google로 로그인"** 버튼 추가. 클릭 시 **전체 페이지 이동**(fetch 아님):
  `window.location.href = \`${OAUTH_BASE}/oauth2/authorization/google\``.
- **`OAUTH_BASE` 구성**(dev/prod 라우팅 차이 — 실질 게이트):
  - **dev**: OAuth 왕복(authorization+callback)을 모두 백엔드 오리진에서 처리해야 forwarded-header 복잡성을 피한다 → `OAUTH_BASE = http://localhost:8080`(vite 프록시 우회, 백엔드 직행). 콜백 redirect_uri = `http://localhost:8080/login/oauth2/code/google`.
  - **prod**: 프론트·백엔드 동일 오리진(nginx) → `OAUTH_BASE = ''`(same-origin), nginx가 `/oauth2/**`·`/login/oauth2/**`를 백엔드로 프록시. 콜백 redirect_uri = `https://sagertc.duckdns.org/login/oauth2/code/google`.
  - 프론트 env `VITE_OAUTH_BASE`로 주입(dev=8080, prod=빈값).
- **핸드오프 수신**(라우터 불필요): App 마운트 시 `location.hash`에 `token=`이 있으면 → 토큰 파싱 → `getMe(token)` → `persistSession(token, user)` → `history.replaceState`로 해시 제거 → 채팅 진입. 루트 `/#token=`으로 받으니 새 라우트·nginx SPA 폴백 이슈 없음.
- `#oauth_error=`가 오면 로그인 화면에 사용자용 메시지 노출.

## 4. 데이터 초기화 + 데모 시드

- **V3**(스키마): `password`를 nullable로, `provider` 컬럼 추가.
  ```sql
  ALTER TABLE members MODIFY password VARCHAR(255) NULL;
  ALTER TABLE members ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
  ```
- **V4**(데모 시드): 데모 회원 2개 + 대화가 있는 방 1~2개 + 멤버십/메시지.
  - 회원: `demo@sage.app` / `guest@sage.app`(이메일·비번, provider=LOCAL). 비번은 **BCrypt 해시를 SQL에 박음**(구현 시 생성).
  - 방: 예) "공지", "잡담" — 두 데모 계정이 이미 참여, 몇 개의 메시지가 오간 상태. 리뷰어가 로그인하자마자 살아있는 채팅을 본다.
  - `chatroom_members.last_read_message_id`는 시드 시점 최신 id로(안읽음 0에서 시작) 또는 일부는 낮게 둬 안읽음 소인 데모까지 보이게 — 구현에서 결정.
- **초기화 방법**: 배포 전 RDS **스키마 drop+recreate**(사장님이 RDS에서 직접, hands-on) → 재배포 시 Flyway가 V1~V4를 fresh 적용 → 깨끗한 스키마 + 데모 시드. (V4 seed를 기존 DB에 얹으면 쓰레기 데이터와 섞이므로 초기화가 전제.)
- 시드 방법은 **Flyway V4**로 확정(CommandLineRunner 대신). 배포 자동 적용 + 재현 가능.

## 5. 배포

- **Google Cloud Console**(사장님 계정): OAuth 2.0 클라이언트 ID 생성.
  - 승인된 리다이렉트 URI: `http://localhost:8080/login/oauth2/code/google`(로컬) + `https://sagertc.duckdns.org/login/oauth2/code/google`(운영).
  - 동의화면 scope: `openid`, `email`, `profile`.
- **env 추가**(EC2 `.env`, 기존 JWT_SECRET 등에 더해): `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `FRONTEND_URL`(=`https://sagertc.duckdns.org`).
  - `application.yaml`에 `spring.security.oauth2.client.registration.google.client-id: ${GOOGLE_CLIENT_ID}` 등. 로컬은 `application-local`에.
- **nginx**: `/oauth2/`·`/login/oauth2/` location을 백엔드로 프록시 추가(현재 `/api`·`/ws`만). 빠지면 콜백 404.

## 6. 검증

- **백엔드 단위 테스트**(`@SpringBootTest`, H2):
  - find-or-create: 기존 email → 같은 회원 반환(자동 연결), 새 email → 생성(password null, provider GOOGLE, nickname 10자 절단).
  - JWT 발급이 memberId/email을 담는지.
  - 이메일/비번 로그인이 password null인 소셜 계정에 대해 **거부**되는지(비번 없는 계정은 비번 로그인 불가).
- **프론트**: 핸드오프 로직을 **모의 토큰**으로 검증(해시에 유효 토큰 심어 로드 → getMe → 세션 저장 → 해시 제거). lint+build.
- **제약(정직)**: 실제 Google 로그인 화면 통과는 자동화가 자격증명을 넣을 수 없다(넣어서도 안 됨). → **실 구글 왕복은 사장님이 로컬/배포에서 직접 확인**(Google Console 앱도 사장님이 생성하니 자연스럽게 hands-on). 자동화는 "구글 경계 직전(진입 리다이렉트 생성)"과 "핸드오프 이후(프론트)"를 검증.
- **Flyway**: 빈 MySQL에 V1~V4 적용 후 `validate` 통과 부팅 + 데모 데이터 존재 확인(도입 편과 동일 방식).

## 7. 엣지케이스 / 알려진 한계

- **자동 연결 takeover 위험**: 이메일/비번 회원가입이 메일 소유를 검증하지 않으므로, 남의 gmail로 미리 비번가입해두면 진짜 주인의 구글 로그인이 그 계정으로 들어갈 수 있다. **학습/포트폴리오 규모에서 수용**하고 여기에 한계로 명시. 후속으로 이메일 인증을 붙이면 닫힌다.
- **비번 없는 소셜 계정 + 비번 로그인 시도**: `password`가 null이므로 `passwordEncoder.matches`에 넣기 전에 null 가드 → `INVALID_PASSWORD` 또는 "소셜 로그인 계정입니다" 안내.
- **Google name > 10자 / 이름 없음**: nickname 컬럼 length 10 → 앞 10자 절단, 없으면 email local-part 등 폴백.
- **닉네임 중복**: 닉네임은 unique가 아님(식별은 email) → 중복 허용, 문제 없음.
- **로그아웃**: 서버 세션 없음(STATELESS JWT) → 기존대로 localStorage 비우기. OAuth가 바꾸지 않음. (구글 세션 자체는 유지 — 앱 로그아웃만.)

## 8. 의존성·브랜치

- 브랜치 `feat/oauth-google` → develop PR.
- 스키마 변경 있음 → **Flyway V3/V4로 자동 적용**(수동 ALTER 금지, Flyway 도입 효과). 배포 전 RDS 초기화는 사장님이 실행.
- Google Console 앱 생성 + env 3개는 사장님(hands-on). 배포 순서: RDS 초기화 → env 채우기 → nginx 프록시 추가 → 머지/배포.
