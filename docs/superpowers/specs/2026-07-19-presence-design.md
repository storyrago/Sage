# Presence (온라인 상태) 설계

**날짜:** 2026-07-19
**범위:** 실시간 접속자(presence) — 전역 온라인/오프라인 상태만. 타이핑·방 위치는 이후 확장.
**저장/전파:** in-memory + `SimpMessagingTemplate` 직접 브로드캐스트 (Redis 미사용).

## 목표

채팅 참가자가 **지금 접속 중인지**를 실시간으로 표시한다.
- 참가자 명단의 각 멤버에 온라인 점 표시
- "온라인 N명" 카운트

명시적 비목표(이번 범위 아님):
- 타이핑 인디케이터 (프론트에 가짜 UI 있으나 손대지 않음)
- "어느 방에 있는지" 방 위치 추적 (다음 확장)
- 멀티 인스턴스 대비 Redis 공유 (단일 인스턴스라 YAGNI)

## 핵심 결정

| # | 결정 | 이유 |
|---|------|------|
| 1 | 온라인 = **전역 WebSocket 세션 생존** | 방 무관하게 "접속 중"이라는 일상적 의미. 세션 생명주기 이벤트로 깔끔하게 추적. |
| 2 | 상태를 **in-memory**에 두고 `SimpMessagingTemplate`로 직접 방송 | 실제 배포가 단일 인스턴스 → Redis 이점 미발동. 학습 초점은 STOMP 세션 이벤트. |
| 3 | 로스터에 **memberId만** 담음 | 프론트가 참가자 프로필을 이미 보유 → 닉네임/아바타 교차참조 가능. |
| 4 | 매 변경마다 **전체 스냅샷** 방송(델타 아님) | 멱등·단순. 온라인 셋 규모가 작음. |

## 데이터 모델

서버 상태:
```
ConcurrentHashMap<String sessionId, Long memberId>
```
- **온라인 memberId 집합** = map values의 distinct 집합.
- 한 멤버가 탭 여러 개(세션 여러 개) → 여전히 1명. 마지막 세션이 사라져야 오프라인.

브로드캐스트 페이로드 (`/sub/presence`):
```json
{ "onlineMemberIds": [1, 5, 8] }
```

## 백엔드

기존 메시지 흐름(참고): `SEND /pub/chatrooms/{id}/messages` → `ChatMessageController` → `RedisPublisher`(토픽 `chatroom`) → `RedisSubscriber` → `convertAndSend("/sub/chatrooms/{id}", MessageResponse)`. CONNECT 시 `WebSocketConfig`의 `ChannelInterceptor`가 JWT 검증 후 `accessor.setUser(UsernamePasswordAuthenticationToken(CustomUserDetails(memberId, email)))` 설정.

**신규 3개:**

### `PresenceRegistry` (`@Component`)
스레드-세이프 세션 추적. Redis·DB 없음.
- `void connect(String sessionId, Long memberId)`
- `Optional<Long> disconnect(String sessionId)` — 제거된 memberId 반환(없으면 empty)
- `Set<Long> getOnlineMemberIds()` — 현재 온라인 memberId distinct 집합

### `PresenceResponse` (DTO)
- `List<Long> onlineMemberIds`
- 정렬은 선택(안정적 출력 원하면 오름차순). 프론트는 Set으로 받으니 순서 무관.

### `WebSocketEventListener` (`@Component`)
`SimpMessagingTemplate`와 `PresenceRegistry` 주입. `@EventListener`로 처리:

- **`SessionConnectedEvent`**:
  - Principal에서 memberId 추출(`event.getUser()` → `UsernamePasswordAuthenticationToken` → `CustomUserDetails.getMemberId()`).
  - sessionId 추출(`StompHeaderAccessor.wrap(event.getMessage()).getSessionId()`).
  - `registry.connect(sessionId, memberId)` → 로스터 방송.
  - Principal이 없거나 CustomUserDetails가 아니면(인증 안 된 연결) 무시.
- **`SessionDisconnectEvent`**:
  - `event.getSessionId()` → `registry.disconnect(sessionId)` → 로스터 방송.
- **`SessionSubscribeEvent`** (destination이 `/sub/presence`일 때만):
  - **현재 로스터를 즉시 방송.** 방금 접속한 클라이언트는 자기 connect 방송을 (아직 구독 전이라) 놓치므로, presence 구독 시점에 최신 스냅샷을 재방송해 late-join 동기화.
  - destination은 `StompHeaderAccessor.wrap(event.getMessage()).getDestination()`로 확인.

방송 헬퍼:
```
messagingTemplate.convertAndSend("/sub/presence",
    new PresenceResponse(new ArrayList<>(registry.getOnlineMemberIds())));
```

**손대지 않는 것:** `WebSocketConfig`(브로커/인터셉터 그대로), `RedisConfig`, `RedisSubscriber`, 메시지 파이프라인 전체.

## 프론트엔드

### `stomp.ts` — 다중 구독 라우팅으로 리팩터 (기존 코드 손대는 유일 지점)
현재 제약: 구독 1개(`currentSubscription`) + `onMessage`가 모든 MESSAGE를 `BackendMessage`로 단정.

변경:
- STOMP `MESSAGE` 프레임의 **`subscription` 헤더**로 핸들러 라우팅.
- 구독 관리: 채팅방 구독(방 전환마다 교체, 기존 동작 유지)과 presence 구독(연결 시 1회, destination `/sub/presence`)을 분리 관리.
- `StompClientOptions`에 `onPresence: (onlineMemberIds: string[]) => void` 추가.
- `onConnect` 이후 presence 구독을 걸고, 해당 subscription id로 오는 프레임은 `onPresence`로, 채팅방 subscription id로 오는 프레임은 기존 `onMessage`로 분기.
- 기존 `subscribe(chatroomId)` / `send()` 시그니처·동작은 유지.

### `App.tsx`
- state 추가: `onlineMemberIds: Set<string>` (문자열 id로 통일 — 기존 코드가 `String(memberId)` 사용).
- `SpringStompClient` 옵션에 `onPresence` 연결 → 받은 배열로 Set 교체.
- `ChatArea`에 `onlineMemberIds` prop 전달.
- 로그아웃/연결 해제 시 초기화(`clearSession`에 `setOnlineMemberIds(new Set())` 추가).

### `ChatArea.tsx`
- 참가자 명단(`participants`) 각 항목: `onlineMemberIds.has(String(member.id))`면 온라인 점(초록) 표시.
- **참가자 패널 헤더**의 "참가자 N명"을 "참가자 N명 · 온라인 M명"으로 확장(M = 명단 중 온라인인 멤버 수). 위치는 이 한 곳으로 고정.
- 기존 typing UI·`presences` plumbing은 그대로 둠.

### `types.ts` / `api.ts`
- `PresencePayload` 타입: `{ onlineMemberIds: number[] }` (백엔드 응답 형태). 소소한 추가.

## 검증

프론트 유닛테스트 없음 → 수동 E2E + 컴파일 검증.

1. **백엔드**: `./gradlew build` — 컴파일 + 기존 테스트 통과.
2. **프론트**: `npm --prefix frontend run lint`(tsc) + `vite build`.
3. **수동 E2E** (로컬 dev 5173 + 백엔드 8080 + Redis):
   - 브라우저 탭 2개, 계정 2개(A/B) 로그인.
   - A 접속 → B의 참가자 명단에 A 온라인 점 + "온라인 N명" 증가.
   - A 탭 닫기 → 몇 초 내 B에서 A 오프라인 + 카운트 감소.
   - A가 탭 2개 열고 하나만 닫음 → 여전히 온라인(마지막 세션까지 살아있음).

## 배포 영향

- **DB 스키마 변경 없음** (in-memory 상태). `ddl-auto: validate` 무관, 마이그레이션 불필요.
- develop 머지 → CD 자동배포 안전.

## 향후 확장 (이번 범위 밖)

- **방 위치**: `SessionSubscribeEvent`/`SessionUnsubscribeEvent`로 세션↔방 매핑 추가 → 로스터에 `currentRoomId`.
- **멀티 인스턴스**: presence 상태를 Redis로 이전 + presence 전용 토픽/타입 봉투로 pub/sub 공유.
- **타이핑**: 기존 `Presence` 타입/UI를 실제 STOMP 브로드캐스트로 연결.
