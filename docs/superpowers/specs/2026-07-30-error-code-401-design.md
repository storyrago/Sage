# 오류 코드와 세션 만료 설계

- 작성일: 2026-07-30
- 범위: `global/exception/*`, `SecurityConfig`, `frontend/src/lib/api.ts`, `App.tsx`, `Welcome.tsx`
- 후속 작업의 선행조건: 인가(비공개방) — 별도 설계

## 1. 배경과 목표

두 문제가 같은 뿌리에서 나온다. **오류가 프론트에 구조화된 형태로 오지 않는다.**

**하나. 오류 응답에 코드가 없다.**
`ErrorResponse`는 `message` 하나만 담는다(`GlobalExceptionHandler`). 그래서 프론트는 사유를 구분하려고 **문자열을 매칭한다**:

```ts
// frontend/src/lib/api.ts — joinChatRoom
if (!message.includes('ALREADY_JOINED_ROOM') && !message.includes('already') && !message.includes('이미 참여')) {
  throw error;
}
```

방을 다시 열 때마다 서버는 409 `이미 참여 중인 채팅방입니다.`를 반환하고, 프론트는 이 문자열 매칭으로 그것을 삼킨다. **메시지 문구가 바뀌면 매칭이 깨지고, 재입장이 매번 실패로 처리된다** — 실패 알림 작업 이후로는 그때마다 랜딩으로 튕긴다. 다음 작업(인가)에서 join 거부 사유가 하나 더 늘어나므로 이 방식은 더 못 버틴다.

**둘. 세션이 만료되면 사용자가 이유를 알 수 없다.**
access token은 1시간이고 refresh가 없다. 만료되면 `JwtAuthenticationFilter`가 인증을 세팅하지 않고 `anyRequest().authenticated()`에 걸리는데, `SecurityConfig`에 `exceptionHandling`이 없어 **`oauth2Login`이 등록한 기본 엔트리포인트가 구글 로그인으로 302 리다이렉트**한다. 브라우저 `fetch`는 cross-origin에서 막혀 `TypeError`가 되고, 사용자에게는 "네트워크에 연결할 수 없어요"로 보인다. 실제 원인은 세션 만료다.

목표는 두 가지다.

1. **오류를 코드로 구분할 수 있게 한다** — 문자열 매칭을 없앤다
2. **세션 만료를 세션 만료라고 알린다** — API는 401 JSON을 반환하고, 프론트는 재로그인을 안내한다

## 2. 결정 사항

**D1. 오류 응답에 `code`를 싣는다.**
`ErrorResponse`에 `code`를 추가하고 `GlobalExceptionHandler`의 모든 핸들러가 `errorCode.name()`을 넣는다. 기존 `message`는 그대로 두므로 **기존 소비자는 깨지지 않는다**(필드 추가만).

**D2. 프론트는 코드를 보존하는 오류 타입으로 던진다.**
`lib/api.ts`에 `ApiError extends Error`를 두고 `status`와 `code`를 함께 담는다. `request()`가 이것을 던지므로 호출부가 사유별로 분기할 수 있다. `Error`를 상속하므로 기존 `toUserMessage(err, fallback)` 처리와 그대로 호환된다.

`joinChatRoom`의 문자열 매칭을 `error.code === 'ALREADY_JOINED_ROOM'` 비교로 바꾼다.

**D3. `/api/**`는 미인증 시 401 JSON을 반환한다.**
`SecurityConfig`에 `exceptionHandling`을 추가해 `/api/**`에만 커스텀 `AuthenticationEntryPoint`를 건다. 응답은 오류 응답과 같은 형태(`{"code":"UNAUTHORIZED","message":"..."}`)로 통일한다.

**`/oauth2/**`·`/login/oauth2/**`는 건드리지 않는다.** 소셜 로그인 진입은 리다이렉트가 정상이다.

*대가*: Swagger UI(`/swagger-ui/**`)와 `/v3/api-docs/**`는 이미 permitAll이라 영향이 없다. 그 외 `/api/**` 밖의 미인증 요청은 지금처럼 로그인 리다이렉트를 받는다.

**D4. 프론트는 401을 세션 만료로 처리한다.**
`lib/api.ts`에 401 처리기를 **등록**하는 함수를 둔다. `request()`가 401을 만나면 등록된 처리기를 호출한 뒤 예외를 던진다. App은 마운트 시 처리기를 등록해 세션을 정리하고 로그인 화면에 안내를 띄운다.

*대안이었던 것*: 모든 호출부가 401을 각자 확인하는 방식. 호출 지점이 열 곳이 넘고, 새 API를 추가할 때마다 빠뜨리기 쉽다.

*대가*: 모듈 수준의 가변 상태가 하나 생긴다. 등록은 App 한 곳에서만 하고, 처리기는 세션 정리 외의 일을 하지 않는다.

**D5. 로그인 화면의 안내 문구를 한 곳으로 모은다.**
`Welcome`의 `oauthError` prop은 이제 소셜 로그인 실패와 세션 만료 두 경로가 함께 쓴다. 이름이 사실과 어긋나므로 `notice`로 바꾼다.

**D6. 미사용 `dto/ErrorResponse`를 지운다.**
`global/exception/ErrorResponse`와 이름이 같은데 어디서도 쓰이지 않는다(전체 grep 확인). 오류 응답을 손대는 김에 정리한다 — 둘 중 어느 것을 고쳐야 하는지 헷갈리게 만드는 물건이다.

## 3. 구성 요소

| 파일 | 책임 | 작업 |
|---|---|---|
| `global/exception/ErrorResponse.java` | `code` 필드 추가 | 수정 |
| `global/exception/GlobalExceptionHandler.java` | 모든 응답에 코드 포함 | 수정 |
| `global/exception/ApiAuthenticationEntryPoint.java` | 미인증 시 401 JSON | 생성 |
| `config/SecurityConfig.java` | `/api/**`에 엔트리포인트 연결 | 수정 |
| `dto/ErrorResponse.java` | 미사용 중복 | 삭제 |
| `frontend/src/lib/api.ts` | `ApiError`, 401 처리기 등록, `joinChatRoom` 코드 비교 | 수정 |
| `frontend/src/App.tsx` | 401 처리기 등록, 만료 안내 | 수정 |
| `frontend/src/components/Welcome.tsx` | `oauthError` → `notice` | 수정 |

## 4. 동작

```
API 요청 → 401
  request()가 등록된 401 처리기 호출 → App: 세션 정리 + "세션이 만료되었어요. 다시 로그인해 주세요."
  이어서 ApiError(status=401, code='UNAUTHORIZED') 던짐 → 호출부의 기존 오류 처리도 그대로 동작

방 재입장 → 409 ALREADY_JOINED_ROOM
  joinChatRoom이 code로 판별해 삼킴 (문자열 매칭 아님)
```

## 5. 검증

- `./gradlew test` — 오류 응답에 `code`가 실리는지, 미인증 `/api/**` 요청이 401 JSON을 받는지(리다이렉트가 아님)
- `cd frontend && npm run lint && npm run build`
- 배포 후: 토큰을 만료시키거나 `localStorage`의 토큰을 손상시킨 뒤 아무 동작 → **로그인 화면에 "세션이 만료되었어요"가 보인다**(지금은 "네트워크에 연결할 수 없어요")
- 배포 후: 같은 방에 두 번 입장 → 정상 진입한다(재입장이 랜딩으로 튕기지 않는다)

## 6. 남는 것

- **refresh token은 이번 범위가 아니다.** 만료를 알려주기까지만 한다. 1시간마다 재로그인해야 하는 것은 그대로다.
- **STOMP는 CONNECT 때 한 번만 인증한다.** 토큰이 만료돼도 이미 열린 소켓은 살아 있어, REST는 401인데 실시간 수신은 계속되는 불일치가 남는다. 기존 한계이며 이번에 다루지 않는다.
- OAuth 핸드오프 중 `getMe`가 401이면 401 처리기와 핸드오프 catch가 각각 문구를 설정해 안내가 겹칠 수 있다. 마지막에 설정된 문구가 보이며 둘 다 "다시 로그인" 취지라 실害는 없다.
