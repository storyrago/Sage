# 방 인가 설계

- 작성일: 2026-07-31
- 범위: `config/WebSocketConfig`, 신규 인가 컴포넌트, `ChatRoom*`·`Message` 서비스·컨트롤러, `frontend/src/{App.tsx,lib/stomp.ts,lib/api.ts}`
- 선행 작업: 오류 코드와 세션 만료 설계(2026-07-30) — `code` 기반 분기와 401 엔트리포인트를 이 설계가 그대로 확장한다
- 분할: PR 0(프론트 선행) → PR 1(WS 인가) → PR 2(REST 인가) → PR 3(S3 태깅 계약)

## 1. 배경과 목표

인증은 갖춰져 있다. HTTP는 `JwtAuthenticationFilter` + `anyRequest().authenticated()`, WebSocket은 STOMP `CONNECT`에서 JWT를 검증한다.

인가는 체계가 없다. "방 멤버인가"를 판단하는 코드가 `MessageService`(`:43`, `:72`)와 `ChatRoomMemberService`(`:58`, `:80`)에 흩어져 있고, **검사를 넣어야 막히는 구조**다. 넣는 것을 잊으면 컴파일도 테스트도 통과하고 조용히 열린다.

그 결과 두 경로의 권한이 다르다. REST `GET /api/chatrooms/{id}/messages`는 멤버십을 요구하는데(`MessageService:72`), 같은 자원을 실시간으로 받는 STOMP 구독에는 검사가 전혀 없다.

목표는 세 가지다.

1. **"방 멤버만 그 방의 것을 읽고 쓴다"를 REST와 WS 양쪽에 같은 규칙으로 적용한다**
2. **기본을 거부로 바꾼다** — 규칙을 쓰지 않은 목적지는 막힌 채로 시작한다
3. **판단 지점을 하나로 모은다** — 멤버십 판정 코드가 레포에 하나만 존재한다

## 2. 확인된 결함

전수 감사(42개 에이전트, 제기 37건 중 적대적 검증 통과 19건)에서 확정된 것 중 이 설계가 다루는 항목이다.

**A1. STOMP 구독에 인가가 없고, 인증조차 강제되지 않는다.**
`WebSocketConfig:46`의 인터셉터는 `CONNECT`에서 토큰이 없거나 유효하지 않아도 예외 없이 `return message`(`:71`)한다. `accessor.setUser(...)`(`:66`)만 건너뛴다. `/ws/**`는 `permitAll`이고 `SUBSCRIBE`에는 검사가 없으므로, **미인증 클라이언트가 임의 방을 구독해 실시간 메시지를 수신할 수 있다.** `WebSocketEventListener:41`이 `memberId == null`이면 조용히 반환하므로 접속자 명단에도 나타나지 않는다.

**A2. 구독 destination이 브로커에서 패턴으로 취급된다.**
`enableSimpleBroker("/sub", "/queue")`(`WebSocketConfig:28`)의 prefix는 `startsWith` 검사이고, `DefaultSubscriptionRegistry:159`가 구독 destination의 패턴 여부를 판정해 `:285`에서 `pathMatcher.match(구독패턴, 발신목적지)`로 배달한다. 따라서 `SUBSCRIBE /sub/**` 하나로 전 방의 메시지·타이핑·프레즌스를, `SUBSCRIBE /queue/**`로 전 사용자의 안읽음 이벤트를 받는다.

**인가 매처를 붙여도 이것만으로는 막히지 않는다.** `SUBSCRIBE /sub/chatrooms/*`는 매처가 단일 방으로 보고 통과시키지만 브로커는 패턴으로 등록한다.

**A3. 토큰 만료가 세션 수명 중 강제되지 않는다.**
`CONNECT` 시 1회 검증이 전부다. 한 번 연결된 소켓은 토큰이 만료된 뒤에도 유지된다.

**A4. 프론트에 구독/join 경쟁 조건이 있다.**
`App.tsx:284`가 `loadMessages()`를 `await` 없이 호출하고 다음 줄에서 동기적으로 구독한다. `loadMessages` 내부 첫 await가 `joinChatRoom`의 fetch(`App.tsx:235` → `api.ts:87`)이므로 제어가 즉시 반환되어 **첫 입장에서 SUBSCRIBE가 join 커밋보다 먼저 도착한다.** 현재는 구독에 검사가 없어 증상이 없다.

**A5. 인가 거부를 표현할 수단이 프론트에 없다.**
`stomp.ts:17`의 `onError: () => void`는 인자가 없다. STOMP ERROR 프레임의 `message` 헤더는 래핑된 `MessageDeliveryException` 문자열이라 사유를 구분할 수 없다.

**A6. 오류 종류를 가리지 않고 세션을 지운다.**
`App.tsx:196-218`이 `getMe`와 `refreshRooms`를 한 `try`에 묶고 `catch`가 무조건 `clearSession()`을 한다. `api.ts:76`은 상태코드와 무관하게 던지므로 403·500·502·네트워크 오류가 모두 여기로 온다. 또한 `clearSession`이 `setNotice(null)`(`App.tsx:111`)을 하므로, 401 핸들러(`App.tsx:115-121`)가 띄운 세션 만료 안내가 덮인다.

**A7. 메시지 수정·삭제가 경로의 방 id를 쓰지 않고 멤버십도 보지 않는다.**
`MessageController:54-75`의 `@PathVariable Long chatroomId`가 본문에서 참조되지 않는다. 검사는 메시지 작성자 여부뿐이다(`MessageService:88`, `:104`). 방을 나가도 예전 메시지의 수정·삭제가 가능하고 결과가 방 전체에 재전파된다.

**A8. 타이핑 전송에 멤버십 검사가 없다.**
`ChatMessageController:46-56`이 `chatroomId`를 존재 확인 없이 그대로 전파한다. 비멤버의 닉네임과 memberId가 방 화면에 노출된다.

**A9. 회원 이메일이 인증만으로 노출된다.**
`GET /api/members`(`MemberController:23`)가 전 회원을 필터 없이 반환하고 `MemberResponse:13`에 `email`이 있다. `GET /api/members/{id}`(`:37`)도 호출자 대조 없이 임의 회원을 반환하며 id가 순차값이라 열거된다. 프론트는 이 email을 화면에 사용하지 않는다.

**A10. 임의 URL로 타인의 S3 객체에 orphan 태그를 붙일 수 있다.**
`PATCH /api/members/me/profile-image`가 URL 문자열을 검증 없이 받고(`ProfileImageRequest:11`은 `@NotBlank`뿐), 교체 시 옛 URL에 `ImageDereferencedEvent`를 발행한다(`MemberService:48-58`). 방어선은 버킷 접두사 검사뿐이므로(`S3Service:51`, `:70`) 같은 버킷의 타인 객체 URL이 통과한다. 메시지 `imageUrl`로도 같은 조작이 가능하다(`MessageService:55`, `:107`). 대상 URL 수집은 A9로 1회에 끝난다.

> 실제 삭제 여부는 **미확인**이다. 수명주기 규칙과 IAM 권한은 AWS 콘솔 설정이라 저장소로 확인할 수 없다. 코드로 확정된 범위는 "타인의 살아있는 객체에 `orphan=true`를 붙일 수 있다"까지다.

## 3. 작업 분할

한 PR로 묶지 않는다. 실패 모드가 서로 다르고, 섞으면 리뷰가 성립하지 않는다.

| PR | 내용 | 이 순서인 이유 |
|---|---|---|
| **0** | 프론트 선행 수정 (A4·A5·A6) | 인가를 켜기 전에 있어야 한다. 없으면 켜는 순간 첫 입장마다 연결이 끊기고 재연결 루프가 돈다 |
| **1** | WS 인가 (A1·A2·A3·A8) | 실제로 열려 있는 경로 |
| **2** | REST 인가 (A7·A9 + 알려진 REST 구멍) | WS와 같은 규칙으로 묶는다 |
| **3** | S3 태깅 계약 (A10) | 인가와 무관한 별도 취약점 |

PR 0을 먼저 하지 않으면, 인가가 정확히 동작해도 사용자에게는 "채팅이 몇 초마다 끊긴다"로 보인다.

## 4. 거부 모델

PR 0과 PR 1에 걸치는 결정이므로 먼저 확정한다.

**D1. 거부를 두 등급으로 나눈다.**

| 등급 | 상황 | 동작 |
|---|---|---|
| 인증 실패 | `CONNECT`에 유효 토큰 없음 | 연결 거부, 세션 종료 |
| 인가 실패 | 인증됨, 해당 방 비멤버 | 프레임 폐기 + 개인 오류 채널로 사유 전송, **세션 유지** |

인증 실패는 알릴 대상(개인 큐)이 없고 클라이언트가 할 일이 재시도가 아니라 토큰 갱신이다. 인가 실패는 나머지 방이 정상이므로 소켓 전체를 끊을 이유가 없다.

어휘는 기존 것을 확장한다 — 인증 실패는 `UNAUTHORIZED`, 인가 실패는 `NOT_JOINED_ROOM`. 프론트에는 이미 `code` 기반 분기 패턴이 있다(`api.ts:118`).

**D2. 인가 실패는 예외를 던지지 않는다.**

`preSend`에서 예외를 던지면 `StompSubProtocolHandler:420`이 세션을 `CloseStatus.PROTOCOL_ERROR`(1002)로 닫는다. 커스텀 `StompSubProtocolErrorHandler`를 등록해도 ERROR 프레임을 반환하면 `:544`에서 동일하게 닫힌다. **세션을 유지하려면 예외를 던지지 않는 방법뿐이다.**

```
인가 실패 → preSend가 null 반환 (프레임 폐기, 예외 없음)
          → /user/queue/errors 로 { code, message, destination } 전송
```

`null` 반환만 하면 "연결됨으로 보이는데 아무것도 오지 않는" 상태가 되므로 오류 채널이 필수다. `destination`을 실어 보내 어느 구독이 거부됐는지 특정할 수 있게 한다.

**D3. 와일드카드 destination은 매처보다 먼저 거부한다.**

A2 대응이다. destination에 `*` 또는 `?`가 포함되면 규칙 평가 이전에 거부한다. 정상 클라이언트는 리터럴 destination만 보내므로 부작용이 없다. 이것은 인가 판단이 아니라 **입력 검증**이므로 멤버십 조회보다 앞에 둔다.

**D4. 탈퇴·강퇴 시 해당 세션을 종료한다.**

`SUBSCRIBE` 시점 1회 검사만으로는 방을 나간 뒤에도 수신이 계속된다. `PresenceRegistry`가 이미 `sessionId → 방 → subId → memberId` 매핑을 보유하므로(`WebSocketEventListener:44`), 탈퇴 시 해당 세션을 찾아 닫는다. 재연결 후 `SUBSCRIBE`가 규칙에 걸려 거부된다.

## 5. PR 1 — WS 인가

### 5-1. 규칙 (first-match-wins, 위에서부터)

순서가 곧 보안이다. 넓은 규칙이 위에 오면 아래의 좁은 규칙은 실행되지 않으며, 실패 방향이 "조용한 허용"이라 리뷰에서 드러나지 않는다.

| # | 대상 | 판정 |
|---|---|---|
| 0 | destination에 `*`·`?` 포함 | 거부 |
| 1 | `CONNECT` | 인증됨 |
| 1' | `DISCONNECT`, `UNSUBSCRIBE`, `HEARTBEAT` | 허용 |
| 2 | SUB `/user/queue/unread`, `/user/queue/errors` | 인증됨 |
| 3 | SUB `/sub/chatrooms/{chatroomId}`, `.../typing`, `.../presence` | 방 멤버 |
| 4 | SEND `/pub/chatrooms/{chatroomId}/messages`, `.../typing` | 방 멤버 |
| 5 | 그 외 전부 | 거부 |

```java
messages
    .matchers(wildcardDestination()).denyAll()
    .simpTypeMatchers(CONNECT).authenticated()
    .simpTypeMatchers(DISCONNECT, UNSUBSCRIBE, HEARTBEAT).permitAll()
    .simpSubscribeDestMatchers("/user/queue/unread", "/user/queue/errors").authenticated()
    .simpSubscribeDestMatchers("/sub/chatrooms/{chatroomId}",
                               "/sub/chatrooms/{chatroomId}/typing",
                               "/sub/chatrooms/{chatroomId}/presence").access(roomMember)
    .simpMessageDestMatchers("/pub/chatrooms/{chatroomId}/messages",
                             "/pub/chatrooms/{chatroomId}/typing").access(roomMember)
    .anyMessage().denyAll();
```

세 목적지를 **명시적으로 나열한다.** `/sub/chatrooms/{id}/**` 한 줄이 짧지만 새 목적지가 자동으로 열린다. 기본 거부를 택한 취지와 어긋난다. 또한 `/sub/chatrooms/*`는 한 세그먼트만 매칭하므로 `/typing`·`/presence`가 빠진다.

`DISCONNECT`·`UNSUBSCRIBE`·`HEARTBEAT`를 허용으로 두는 것은 안전하다. `CONNECT`가 인증을 요구하므로 이후 프레임은 모두 인증된 세션에서만 온다. 반대로 이들을 `authenticated()`로 묶으면 만료된 세션이 정리되는 순간 거부되어 종료 처리가 꼬인다. 세션 수명 중 만료는 5-4에서 별도로 다룬다.

`/queue/**` 직접 구독은 별도 규칙 없이 5번에 걸린다. 허용 목록에 `/user/queue/...`만 두면 충분하다.

`/pub` 접두사는 규칙에 포함해야 한다(`setApplicationDestinationPrefixes("/pub")`, `WebSocketConfig:29`). `/user/queue/unread`는 인바운드에서 변환 전 원본 destination이 보이므로 그대로 매칭된다.

### 5-2. 인터셉터 배선

`@EnableWebSocketSecurity`를 쓰지 않는다. 자동 설정 `WebSocketMessageBrokerSecurityConfiguration`은 `@Order(HIGHEST_PRECEDENCE + 100)`이라 `@Order`가 없는 `WebSocketConfig`보다 먼저 실행되어, JWT 인터셉터의 `setUser`(`:66`) **이전에** 인가가 평가된다. 또한 `XorCsrfChannelInterceptor`가 생성자에서 무조건 등록되며 CSRF는 이 애노테이션으로 끌 수 없다(`SecurityConfig:41`의 `csrf.disable()`은 HttpSecurity 한정).

스톡 `AuthorizationChannelInterceptor`도 쓰지 않는다. 무조건 `AccessDeniedException`을 던지므로 D2를 만족할 수 없고, `ChannelInterceptor`는 다음 인터셉터를 감싸는 구조가 아니라 뒤에서 던진 예외를 앞에서 잡을 수 없다.

**규칙(`AuthorizationManager`)은 그대로 쓰고 인터셉터만 직접 구현한다.**

```java
registration.interceptors(
    jwtAuthChannelInterceptor,               // 1. 토큰 검증 → accessor.setUser(...)
    new SecurityContextChannelInterceptor(), // 2. simpUser → SecurityContext
    roomAuthorizationInterceptor             // 3. 규칙 평가 + D1의 2단 거부
);
```

```java
var decision = authorizationManager.check(authSupplier, message);
if (decision != null && !decision.isGranted()) {
    if (isConnect(message) || authentication == null) {
        throw new AccessDeniedException(...);   // 인증 실패 → 세션 종료
    }
    sendToUserQueue(user, "/queue/errors", new WsErrorResponse(NOT_JOINED_ROOM, destination));
    return null;                                // 인가 실패 → 폐기, 세션 유지
}
return message;
```

순서가 어긋나면 규칙 내용과 무관하게 전부 거부된다. `SecurityContextChannelInterceptor`는 인가 자체에는 필수가 아니지만(`accessor.getUser()`를 직접 읽을 수 있다), `SecurityContextHolder`가 채워져야 이후 `@MessageMapping`에 `@PreAuthorize`를 붙이거나 서비스 레이어에서 현재 사용자를 참조할 수 있으므로 포함한다.

### 5-3. 멤버십 판정 — 단일 진실 공급원

```java
AuthorizationManager<MessageAuthorizationContext<?>> roomMember = (auth, ctx) -> {
    Long roomId = parseLongOrNull(ctx.getVariables().get("chatroomId"));
    Long memberId = memberIdOf(auth.get());
    if (roomId == null || memberId == null) return new AuthorizationDecision(false);
    return new AuthorizationDecision(roomAccess.isMember(memberId, roomId));
};
```

`roomAccess`가 이 설계의 단일 진실 공급원이다. PR 2에서 REST 검사도 전부 이것을 통과시켜, "방 멤버인가"를 판단하는 코드가 레포에 하나만 존재하게 한다.

조회 비용은 `chatroom_members`의 `(member_id, chatroom_id)` unique 인덱스 한 번이다(`ChatRoomMember:10-13`). 캐시는 두지 않는다 — 강퇴 기능이 생기면 무효화 문제가 따라오므로 그때 함께 다룬다.

### 5-4. 토큰 만료 (A3)

`CONNECT` 시 토큰 만료 시각을 세션 속성에 저장하고, 이후 프레임마다 정수 비교로 확인해 만료 시 세션을 종료한다. 서명 검증이나 DB 조회는 하지 않는다. 이것이 없으면 "로그아웃했는데 실시간 메시지는 계속 온다"가 남는다.

### 5-5. 의존성

`org.springframework.security:spring-security-messaging` — **버전 생략**(`spring-security-bom-7.0.4`가 관리). 현재 classpath에 없다.

`@EnableWebSocketSecurity`는 `spring-security-config` jar에 있어 **의존성 없이도 컴파일이 통과하고 기동 시 `NoClassDefFoundError`로 죽는다.** 이 애노테이션을 쓰지 않더라도, 컨텍스트 로딩 테스트를 PR에 포함해 이 실패 모드를 CI에서 잡는다. develop push가 CD 자동 배포를 트리거하므로 빌드 통과 후 부팅 실패는 특히 비용이 크다.

### 5-6. 구현 중 실측할 것

추측으로 넘기지 않는다.

| 확인 | 실패 시 증상 |
|---|---|
| `ctx.getVariables()`가 `{chatroomId}`를 채우는가 | 비면 조용히 전부 거부. 매처 래핑 시 경로변수 유실 이슈(spring-security#12540) 이력 있음 |
| `AuthorizationManager.check(...)` 시그니처 | Security 7에서 변경 가능성. 컴파일 단계에서 드러남 |
| 의존성 추가 후 `CONNECT` 성립 여부 | 감사도 jar 부재로 실행 검증 불가했던 항목 |

## 6. PR 0 — 프론트 선행 수정

서버는 변경하지 않는다. 현재 동작을 유지한 채 인가를 켤 수 있는 상태를 만든다.

**F1. join 성공 이후에 구독한다 (A4).**

`await loadMessages()`로 바꾸는 것으로는 부족하다. `loadMessages`는 join 실패 경로(`App.tsx:236-243`)에서도 정상 반환하고, await 뒤에 취소 검사가 없어 방 전환 시 이전 방을 늦게 구독한다.

```tsx
useEffect(() => {
  let cancelled = false;
  (async () => {
    const joined = await ensureJoined(roomId);
    if (cancelled || selectedChannelRef.current !== roomId) return;
    if (!joined) return;
    await loadMessages(roomId);
    if (cancelled) return;
    stompRef.current?.subscribe(roomId);
  })();
  return () => { cancelled = true; };
}, [roomId]);
```

순서·실패 처리·취소를 함께 고친다. 재연결 경로(`App.tsx:314`)도 같은 규칙을 따른다.

**F2. `/user/queue/errors`를 구독한다.**

`stomp.ts:140`이 CONNECTED마다 `/user/queue/unread`를 구독하는 자리에 하나 더 추가한다. 페이로드는 `{ code, message, destination }`. PR 0 시점에는 아무것도 오지 않는다. **프론트가 먼저 배포되어 있어야** PR 1 적용 직후 사용자가 사유를 볼 수 있다.

**F3. 연결 오류와 인가 오류를 구분한다 (A5).**

`stomp.ts:17`의 `onError`가 사유를 받도록 시그니처를 바꾸고 두 경로를 분리한다.

| 출처 | 의미 | 반응 |
|---|---|---|
| STOMP ERROR 프레임 | 연결 수준 실패(세션 종료됨) | 재연결 |
| `/user/queue/errors` | 특정 목적지의 인가 거부(세션 유지) | 재연결하지 않음. 해당 방에 안내 |

ERROR 프레임 헤더로는 401/403을 구분할 수 없으므로, 사유는 오류 채널로만 전달하고 ERROR 프레임은 연결 끊김 신호로만 쓴다.

**F4. 재연결에 백오프와 상한을 둔다.**

`App.tsx:298`은 고정 3초에 상한이 없고, 재연결마다 `getUnreadCounts` REST를 동반한다(`App.tsx:318`).

```
1s → 2s → 4s → 8s → 16s → 30s(상한), 각 시도에 ±20% 지터
연속 실패가 상한을 넘으면 자동 재시도 중단 + 새로고침 안내 배너
```

지터는 서버 재기동 시 클라이언트가 같은 시점에 몰려 다시 과부하를 주는 것을 막는다.

**F5. 오류 종류를 가려 세션을 지운다 (A6).**

세션을 지우는 기준을 `code === 'UNAUTHORIZED'`로 한다. `status === 401`은 `INVALID_PASSWORD` 등 다른 401까지 포함하므로 기준이 될 수 없다. 그 외 오류는 세션을 유지하고 표시만 한다.

`clearSession`에서 notice 초기화를 분리한다. 현재는 `clearSession`의 `setNotice(null)`(`App.tsx:111`)이 401 핸들러가 띄운 안내를 덮어, 저장된 토큰이 만료된 채 앱을 여는 가장 흔한 경로에서 안내가 보이지 않는다.

## 7. PR 2 — REST 인가 (개요)

- `GET /api/chatrooms/{id}`, `GET /api/chatrooms/{id}/members`에 멤버십 요구. 판정은 `roomAccess`를 통한다
- 메시지 수정·삭제 인가를 **리소스(메시지 엔티티)의 방** 기준으로 한다(A7). URL의 방 id를 기준으로 하면, 자신이 속한 방 id를 URL에 끼워 넣는 것만으로 우회된다. 실제 라우팅도 엔티티의 방으로 간다(`RedisSubscriber:29`)
- `MessageService.getAllChatRoomMessages`에 멤버십 검사 추가 또는 제거
- `MemberResponse`에서 `email`을 제거하고 공개용 DTO를 분리한다(A9). `GET /api/members`만 막고 `GET /api/members/{id}`를 남기면 열거가 그대로 남는다. `ChatRoomMemberResponse`에는 email이 없으므로 `MemberResponse` 경로만 정리하면 된다

## 8. PR 3 — S3 태깅 계약 (개요)

A10의 근본 원인은 두 진입점이 아니라 `tagAsOrphan`이 **URL 문자열을 참조 해제의 근거로 신뢰한다**는 계약이다. 프로필과 메시지 중 하나만 막으면 다른 쪽이 같은 기능을 제공한다.

태깅 진입점에서 잔여 참조(`messages.image_url`, `members.profile_image_url`)를 질의해 **아직 참조하는 행이 있으면 태깅하지 않는다.** 필요한 데이터는 이미 존재한다. 업로더 컬럼은 미확정 업로드 정리·쿼터에는 필요하지만 이 취약점의 필수 조건은 아니다.

## 9. 남기는 한계와 그 대가

**한계 1 — 배달 시점 재검사를 하지 않는다.**
정석은 메시지 배달 시점에 현재 멤버십을 확인하는 것이다(선례가 이미 있다 — `RedisSubscriber:40`이 안읽음 fan-out에서 매 메시지마다 현재 멤버를 조회한다). 그러려면 `convertAndSend(토픽)` 브로드캐스트를 멤버별 개별 전송으로 바꿔야 하고 프론트 구독 구조까지 바뀐다.

대신 D4(탈퇴 시 세션 종료)를 쓴다. **대가는 그 사용자의 다른 방 구독도 함께 끊긴다는 것이다.** PR 0의 백오프가 있어 체감은 짧은 재연결 한 번이다. 강퇴 기능을 만드는 다음 사이클에서 배달 시점 재검사로 교체한다.

**한계 2 — 방 단위 역할(방장)이 없다.**
`ChatRoom`에 소유자 컬럼이 없고 `ChatRoomController.create`가 인증 사용자를 받지 않아, 생성자가 기록되지 않고 자동 입장도 하지 않는다. 방 삭제·이름 변경·강퇴는 이번 범위 밖이다. 멤버십 게이트가 그 기능들의 전제이므로 순서상 이 설계가 먼저다.

**한계 3 — 모든 방이 목록에 공개되고 자유 입장이다.**
`GET /api/chatrooms`가 전체를 반환하고 입장에 승인이 없다. 이 설계는 "보려면 반드시 입장해야 한다"를 강제할 뿐 비공개방을 만들지 않는다. 목록 노출 축소는 방 탐색 UX와 함께 다뤄야 하는 제품 결정이라 분리한다.

## 10. 검증

**자동 테스트 (거부 경로 중심).** 인가는 "되는 것"이 아니라 "안 되는 것"을 검증해야 한다.

- 미인증 CONNECT가 거부된다
- 비멤버의 `/sub/chatrooms/{id}`·`/typing`·`/presence` 구독이 거부된다
- 비멤버의 `/pub/chatrooms/{id}/messages`·`/typing` 전송이 거부된다
- `SUBSCRIBE /sub/**`, `/sub/chatrooms/*`, `/queue/**`가 거부된다
- 규칙에 없는 목적지가 거부된다(기본 거부 확인)
- 멤버의 정상 구독·전송이 통과한다
- 거부 시 세션이 유지되고 `/user/queue/errors`에 `code`가 도착한다
- 만료 토큰 세션의 프레임이 거부된다
- 컨텍스트 로딩 테스트(5-5의 `NoClassDefFoundError` 방지)

**PR 0 수동 검증 (브라우저).**

| 확인 | 방법 | 기대 |
|---|---|---|
| join이 구독보다 먼저 | Network + WS 프레임 탭에서 새 방 첫 입장 | `POST .../members` 응답 후 `SUBSCRIBE` |
| 백오프 | 백엔드 컨테이너 정지 후 콘솔 관찰 | 재연결 간격 1→2→4→8초 |
| 로그아웃 오작동 수정 | 백엔드 정지 상태로 새로고침 | 로그인 화면으로 튕기지 않고 오류 배너만 |
| 세션 만료 안내 | 만료 토큰으로 접속 | 안내 문구가 남아있음 |

마지막 두 항목은 현재 코드에서 반대로 동작한다.
