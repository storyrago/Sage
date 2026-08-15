# 타이핑 인디케이터 설계 (실시간 "입력 중")

**날짜:** 2026-07-20
**범위:** 채팅방에서 상대가 입력 중이면 "OO님이 입력 중... ✍️"를 실시간 표시.
**전파:** Redis 경유 브로드캐스트 (메시지 파이프라인과 동형). **stateless** — 공유 상태 없음.

## 목표

- A가 입력을 시작하면 같은 방의 다른 참가자에게 "A님이 입력 중"이 뜬다.
- A가 멈추거나(2초 유휴) 메시지를 보내면 사라진다.
- 여러 백엔드 인스턴스에서도 동작(메시지·presence와 동일하게 Redis 경유).

## 비목표

- 타이핑 상태 영속화(당연히 없음, 순간 이벤트).
- "입력 중"의 정확한 텍스트 미리보기(내용은 안 보냄, 여부만).

## 핵심 결정

| # | 결정 | 이유 |
|---|------|------|
| 1 | **Redis "typing" 토픽 경유** 브로드캐스트 | 메시지·presence와 동일 패턴 → 멀티서버 유지. 직접 방송하면 presence에서 메꾼 비대칭이 재발. |
| 2 | **stateless** — 서버는 상태를 안 들고 릴레이만 | 타이핑은 순간 이벤트(메시지 성격). presence의 Redis Hash 같은 공유 상태 불필요. |
| 3 | 방별 목적지 **`/sub/chatrooms/{id}/typing`** (채팅 `/sub/chatrooms/{id}`과 분리) | 프론트 STOMP 클라가 구독별 라우팅하므로 별도 구독으로 깔끔히 분리. 채팅 메시지와 안 섞임. |
| 4 | `true`는 **하트비트(최대 3초마다 1회)**, `false`는 debounce(2초 유휴)·전송 시 | 키 입력마다 publish하면 Redis 폭주 → 3초 스로틀로 트래픽 억제. 단 "1회만"은 안 됨(긴 타이핑 시 수신측 만료가 잘못 꺼짐) → 3초 하트비트로 수신측 타이머를 갱신. |
| 5 | 수신측 **~5초 안전 만료** 타이머(하트비트보다 길게) | `false` 유실·상대 급종료 시 "입력 중" 박제 방지. 하트비트(3초)마다 갱신되므로 정상 타이핑 중엔 안 꺼지고, 끊기면 5초 후 자동 해제. |

## 백엔드

기존 메시지 흐름 참고: `SEND /pub/chatrooms/{id}/messages` → `ChatMessageController` → `RedisPublisher.publish` (토픽 `chatroom`) → `RedisSubscriber` → `convertAndSend("/sub/chatrooms/{id}", …)`. presence는 여기에 `publishPresence`(토픽 `presence`) + `PresenceRedisSubscriber`를 더한 형태.

**신규/변경:**

### `TypingRequest` (DTO, 신규)
- `boolean isTyping`

### `TypingResponse` (DTO, 신규)
- `Long chatroomId` (subscriber 라우팅용), `Long memberId`, `String nickname`, `boolean isTyping`

### `ChatMessageController`에 타이핑 핸들러 추가
```
@MessageMapping("/chatrooms/{chatroomId}/typing")
public void typing(@DestinationVariable Long chatroomId, TypingRequest req, Principal principal)
```
- Principal에서 `CustomUserDetails` → memberId. **nickname은 `MemberService`로 memberId 조회해 채운다**(true 스로틀로 빈도 낮음 → 조회 비용 미미). 프론트는 임의 멤버의 이름을 항상 갖고 있지 않으므로(참가자 패널 열어야 로드) payload에 닉네임을 담아야 안전.
- `TypingResponse` 만들어 `redisPublisher.publishTyping(response)`.

### `RedisPublisher.publishTyping(TypingResponse)` 추가
- `typing` 토픽으로 publish. 기존 `publish`/`publishPresence` 시그니처 무변경.

### `RedisConfig` — `typing` 토픽 빈 + 리스너 등록
- `ChannelTopic typingTopic()` = `"typing"`. `RedisMessageListenerContainer`에 `TypingRedisSubscriber`를 `typingTopic`으로 등록. (ChannelTopic 빈 3개 → 이름 해석)

### `TypingRedisSubscriber` (신규, `MessageListener`)
- `typing` 메시지를 `TypingResponse`로 역직렬화 → `messagingTemplate.convertAndSend("/sub/chatrooms/" + chatroomId + "/typing", response)`.

**무변경:** 메시지·presence 파이프라인, DB.

> nickname 확보: `CustomUserDetails`엔 memberId·email만 있음(nickname 없음). 그래서 타이핑 핸들러에서 memberId로 회원을 조회해 nickname을 채우거나, 프론트가 이미 참가자 프로필을 갖고 있으니 **memberId만 보내고 프론트가 이름 매핑**해도 됨. → 플랜에서 확정(추천: memberId만, 프론트 매핑 — 조회 비용 0).

## 프론트엔드

기존 배선(재사용): `ChatArea` 입력 → `onTypeStateChange(isTyping)` (키 입력 시 true, 2초 debounce·전송 시 false). `typingUsers = presences.filter(같은 방 && isTyping && !self && lastSeen>0)` → "OO님이 입력 중" 바 렌더. 현재 `App.handleTypeStateChange`는 **로컬 자기 presence만** 바꿔서 남에게 안 감.

**변경:**

### `stomp.ts`
- `sendTyping(chatroomId, isTyping)` — `SEND /pub/chatrooms/{id}/typing` `{isTyping}`.
- 방 구독 시(`subscribe(chatroomId)`) `/sub/chatrooms/{id}/typing`도 **함께 구독**. 구독 종류에 `'typing'` 추가 → `onTyping(payload)`로 라우팅. 방 전환 시 채팅·타이핑 구독 함께 교체.
- `StompClientOptions`에 `onTyping?: (p: { chatroomId, memberId, isTyping }) => void` 추가.

### `App.tsx`
- `handleTypeStateChange`에서 **`stompRef.current.sendTyping(...)` 호출**(로컬 presence 세팅 대신). **true 하트비트 스로틀**: 마지막 true 전송 후 3초 안 지났으면 skip(false는 항상 즉시 전송). ref로 `lastTypingSentAt`·`typingActive` 관리.
- `onTyping` 수신 → payload의 `nickname`으로 `presences`에 upsert(userId=memberId, userName=nickname, isTyping, lastSeen=now, channelId=chatroomId). isTyping=false면 해제.
- **안전 만료**: 각 typing:true 수신 시 그 멤버용 ~5초 타이머 세팅(Map<memberId, timeoutId>) → 만료 시 isTyping false. 새 이벤트 오면 리셋. (하트비트 3초 < 만료 5초라 정상 타이핑 중엔 안 꺼짐)

### `types.ts`
- `Presence` 타입 그대로 재사용. `WSMessageType` union은 손대지 않음(타이핑은 `onTyping` 콜백으로 라우팅, union 미사용).

**무변경:** `ChatArea`의 `typingUsers` 필터·UI(이미 완성).

## 검증

1. 백엔드 `./gradlew build` 그린.
2. **E2E (멀티인스턴스 가능)**: 백엔드 2개(8080·8081) 공유 Redis. WS 클라 A(8080)·B(8081)가 같은 방 구독. A가 `/pub/.../typing {isTyping:true}` 전송 → **B가 `/sub/chatrooms/{id}/typing`으로 A의 typing 수신** → true/false 반영 확인.
3. 프론트 `tsc` + `vite build`.

## 배포 영향

- DB 스키마 변경 없음. Redis 토픽/키만 추가. 배포 안전.

## 향후 확장

- presence 죽은 세션 청소(TTL) — 별도.
