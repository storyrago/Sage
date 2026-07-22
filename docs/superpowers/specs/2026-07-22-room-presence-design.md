# 방-스코프 온라인 Presence 설계

**날짜:** 2026-07-22
**범위:** 전역 온라인(어느 방이든 접속=온라인) → **"지금 이 방을 보고 있는 사람만" 온라인**으로 교체.
**전제:** 기존 Redis-backed presence(멀티서버)를 방 단위로 재설계. 스키마 변경 없음(Redis만).

## 목표

- 채팅방 참가자 명단의 온라인 점·"온라인 N명"이 **그 방에 현재 참여 중인 사람만** 반영.
- 방 입장/전환/나가기/접속종료에 따라 실시간 갱신.
- 멀티서버 유지(기존 Redis 릴레이 패턴 재사용).

## "이 방에 있다"의 정의

프론트가 방 입장 시 `/sub/chatrooms/{id}` (채팅)를 구독함 = **그 방을 보고 있음**. 이 채팅 구독을 "방 참여" 신호로 삼는다.
- 방 전환 → 이전 방 채팅 구독 해제 + 새 방 구독(프론트 `subscribe()`가 이미 이렇게 함).
- 랜딩으로 나감 → 방 채팅 구독 해제(프론트에 **신규 추가** 필요; 현재는 유지됨).
- 접속 종료 → 세션 소멸.

## 핵심 결정

| # | 결정 | 이유 |
|---|------|------|
| 1 | 전역 `/sub/presence` **제거**, 방별 `/sub/chatrooms/{id}/presence`로 교체 | 요구가 "방 참여자만". 전역 로스터는 참가자 점에만 쓰였음. |
| 2 | 참여 신호 = **채팅 구독 `/sub/chatrooms/{id}`** | 방을 보고 있다 = 채팅 구독 중. 별도 신호 불필요. |
| 3 | Redis 방별 Hash + 세션 상태 Hash | 방 전환(이전 방 제거)·랜딩(unsubscribe)·접속종료를 정확히 처리. |
| 4 | 방송은 기존 `"presence"` Redis 토픽 재사용, `/sub/chatrooms/{id}/presence`로 라우팅 | 멀티서버 유지. `PresenceResponse`에 `roomId` 추가(라우팅). |

## Redis 모델

```
presence:room:{roomId}   (Hash)  field=sessionId, value=memberId   // 그 방에 있는 세션들
presence:session         (Hash)  field=sessionId, value="{roomId}|{subId}|{memberId}"  // 세션의 현재 방·채팅 구독 id
```
- **방 온라인 memberId** = `HVALS presence:room:{roomId}` 의 distinct.
- `presence:session`은 전환(이전 방 제거)·랜딩(unsubscribe 시 채팅 subId 매칭)·접속종료(현재 방 제거)에 필요.

## 백엔드

### `PresenceRegistry` (재작성)
`StringRedisTemplate` 사용. 메서드:
- `enterRoom(sessionId, roomId, subId, memberId)` → `Optional<Long> previousRoom`:
  - 기존 세션 상태 조회. 이전 방이 있고 새 방과 다르면 `HDEL presence:room:{이전방} sessionId` 하고 이전 방 id 반환(방송용).
  - `HSET presence:room:{roomId} sessionId memberId`
  - `HSET presence:session sessionId "{roomId}|{subId}|{memberId}"`
- `Optional<Long> leaveBySubscription(sessionId, subId)`:
  - 세션 상태 파싱. 저장된 채팅 subId == 인자 subId면 → `HDEL presence:room:{roomId} sessionId`, `HDEL presence:session sessionId`, roomId 반환. (아니면 empty — typing/presence 구독 해제는 무시)
- `Optional<Long> disconnect(sessionId)`:
  - 세션 상태 있으면 → `HDEL presence:room:{roomId} sessionId`, `HDEL presence:session sessionId`, roomId 반환.
- `Set<Long> getRoomOnlineMemberIds(roomId)` = `HVALS presence:room:{roomId}` distinct→Long.

### `WebSocketEventListener` (재작성)
- **`onConnected`**: 제거(연결만으론 어느 방도 아님).
- **`onSubscribe(SessionSubscribeEvent)`**:
  - destination이 **채팅 `/sub/chatrooms/{id}`**(접미사 없음)일 때만 처리. `id` 파싱, subId=`accessor.getSubscriptionId()`, memberId=Principal.
  - `prev = registry.enterRoom(sessionId, id, subId, memberId)` → `id` 방 방송, `prev` 있으면 그 방도 방송.
  - (destination이 `/sub/chatrooms/{id}/presence`나 `/typing`이면 무시 — 참여 신호는 채팅 구독만.)
- **`onUnsubscribe(SessionUnsubscribeEvent)`** (신규):
  - subId=`accessor.getSubscriptionId()`, sessionId. `registry.leaveBySubscription(sessionId, subId)` → 방 id 있으면 그 방 방송.
- **`onDisconnected(SessionDisconnectEvent)`**: `registry.disconnect(sessionId)` → 방 id 있으면 그 방 방송.
- **`broadcastRoom(roomId)`**: `new PresenceResponse(roomId, new ArrayList<>(registry.getRoomOnlineMemberIds(roomId)))` → `redisPublisher.publishPresence(...)`.
- destination 파싱: `/sub/chatrooms/{숫자}$` 정규식으로 채팅 구독만 매칭(`/presence`·`/typing` 제외).

### `PresenceResponse` (변경)
- `Long roomId` 추가: `{ roomId, onlineMemberIds }`. (subscriber 라우팅용)

### `PresenceRedisSubscriber` (변경)
- 역직렬화 후 `convertAndSend("/sub/chatrooms/" + roomId + "/presence", presence)`. (기존 `/sub/presence` → 방별로)

### `RedisConfig`/`RedisPublisher`
- 토픽 `"presence"`·`publishPresence` 그대로 재사용(payload에 roomId 추가된 것뿐).

## 프론트엔드

### `lib/stomp.ts`
- `subscribe(chatroomId)`에서 **방 presence 구독 추가**: `/sub/chatrooms/{id}/presence`. **채팅 구독보다 먼저** 구독(내 입장 방송을 놓치지 않게). 구독 종류 라우팅에 `'roompresence'` 추가.
- `onPresence` 시그니처: **`(roomId: string, onlineMemberIds: string[])`** 로 변경(payload에 roomId 포함). 전역 presence 자동구독(`subscribePresence`)·`PRESENCE_DESTINATION` 제거.
- 방 전환 시 채팅·타이핑·presence 구독 함께 교체(기존 unsubscribe 흐름에 presence도).
- **랜딩 나가기 처리**: 방을 떠날 때(랜딩) 구독 해제 메서드 필요 → `unsubscribeRoom()` 추가(채팅·타이핑·presence UNSUBSCRIBE). 백엔드가 채팅 unsubscribe로 방에서 제거.

### `App.tsx`
- 전역 presence 관련 제거. `onPresence(roomId, ids)` → **roomId가 현재 `selectedChannelId`와 같을 때만** `setOnlineMemberIds(new Set(ids))`(전환 중 늦게 온 이전 방 로스터 무시).
- 방을 떠나 랜딩으로 갈 때(`selectedChannelId` → '') `stompRef.current?.unsubscribeRoom()` 호출 + `setOnlineMemberIds(new Set())`.
- 채팅 구독 시점(`subscribe(selectedChannelId)`)은 기존 이펙트 그대로(방 presence 구독도 그 안에서).

### `ChatArea.tsx`
- 무변경. 참가자 명단 온라인 점·"온라인 N명"이 이제 **방 온라인**을 반영(`onlineMemberIds`가 방 로스터).

## 검증

1. 백엔드 `./gradlew build`.
2. **멀티서버 E2E**: 백엔드 2인스턴스 공유 Redis. WS 클라 A(8080)·B(8081):
   - A가 방 R 채팅 구독 → A가 R의 `/sub/chatrooms/R/presence`로 자기 온라인 수신.
   - B가 R 구독 → A·B 모두 R 로스터에 A·B.
   - B가 **다른 방 S로 전환** → R 로스터에서 B 사라짐(A가 수신), S엔 B.
   - B **채팅 구독 해제(랜딩)** → R/S에서 B 사라짐.
   - B **접속 종료** → 방에서 사라짐.
3. 프론트 `tsc` + `vite build`.

## 배포 영향

- **스키마 변경 없음**(Redis만). 배포 안전. Redis 키(`presence:room:*`, `presence:session`)만 추가.
- 전역 `/sub/presence` 제거 → 프론트·백 동시 배포라 정합성 문제 없음(같은 배포에 포함).

## 알려진 한계

- 서버 하드 크래시 시 Redis 유령 세션 잔류(기존 presence와 동일; TTL은 향후).
