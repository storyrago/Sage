# 로그인 rate limiting (Redis) — 설계·검증

- 날짜: 2026-07-22
- 브랜치: `feat/login-rate-limit` (develop 분기)
- 범위: 로그인 무차별 대입(brute-force) 완화. Redis 고정 창(fixed window) 카운터로 IP당 실패 횟수 제한.

## 문제

`POST /api/auth/login`에 **속도 제한이 전혀 없었다.** 공개 앱이라 한 IP에서 초당 수백 번 시도해도 막는 게 없어, 사전 대입/자격증명 스터핑에 무방비.

## 결정

- **정책**: 한 IP에서 **5분 내 로그인 실패 10회 → `429 TOO_MANY_LOGIN_ATTEMPTS`** (그 창 동안 차단). **성공 시 카운터 리셋.**
- **키 = 클라이언트 IP.** nginx 뒤이므로 `X-Forwarded-For` **첫 IP**를 쓴다(없으면 `remoteAddr`). ⚠️ 이걸 안 하면 모든 요청이 nginx IP 하나로 뭉쳐 **전역 잠금**이 된다 — 이 점이 이 기능의 핵심 함정.
- **Redis 구조**: `login:fail:{ip}` INCR, **첫 실패에만 TTL 5분**. `isBlocked`는 값 ≥ 임계치.
- **fail-open**: Redis 예외 시 차단하지 않는다(가용성 우선 — 레이트리밋 저장소 장애가 로그인 전면 마비로 번지면 안 됨).
- **실패 카운트 대상**: 잘못된 비밀번호 **및 존재하지 않는 이메일** 모두(이메일 프로빙도 실패로 셈). 기존 에러코드(404/401) 구분은 유지.

## 구현

- `LoginRateLimiter`(@Component, `StringRedisTemplate`): `isBlocked / recordFailure / reset`, 전부 try-catch fail-open.
- `AuthService.login(LoginRequest, String clientIp)`: ① 차단 확인 → ② 이메일·비번 검증(실패 시 `recordFailure` 후 기존 예외) → ③ 성공 시 `reset`.
- `AuthController`: `HttpServletRequest`에서 `X-Forwarded-For` 첫 IP 추출해 전달.
- `ErrorCode.TOO_MANY_LOGIN_ATTEMPTS(429, …)` 추가.

## 검증 (실측)

`@SpringBootTest`(로컬 Redis 6379) `LoginRateLimitTest`, `./gradlew test` 전체 통과:
- **실패 10회 → 각각 `INVALID_PASSWORD`, 11번째 → `TOO_MANY_LOGIN_ATTEMPTS(429)`** (비번 검사 전에 차단).
- **성공 시 리셋**: 5회 실패 후 성공하면 키 삭제 확인, 이후 다시 5회 실패해도 아직 임계치 미만(`INVALID_PASSWORD`).
- Redis는 `@Transactional` 롤백 대상이 아니므로 테스트가 `@BeforeEach/@AfterEach`로 키를 직접 정리.

## 범위 밖(짚음)

- **이메일 열거(enumeration)**: 존재하지 않는 이메일엔 404, 틀린 비번엔 401로 응답이 갈려 "이 이메일이 가입돼 있는지"가 새어나간다(기존부터의 동작). 통일된 "자격증명 불일치"로 바꾸는 건 별건.
- **회원가입/기타 엔드포인트** 레이트리밋은 이번 범위 아님.
- 고정 창이라 창 경계에서 버스트 여지가 조금 있다(슬라이딩 윈도우면 더 엄밀). 현 위협 수준엔 충분.
