# Redis-backed Presence 설계 (멀티서버 비대칭 메꾸기)

**날짜:** 2026-07-19
**전제:** 기존 in-memory presence(`feat/presence`, PR #45 머지됨)를 **메시지와 동일한 Redis 경유 구조**로 전환한다.
**동기:** 메시지는 Redis pub/sub으로 멀티서버 대응되는데 presence만 단일서버 전용(인메모리) → 비대칭. 서버를 여러 대로 늘리면 presence가 깨짐(각 서버가 자기 세션만 알고, `convertAndSend`가 자기 서버 클라에만 닿음). 이 비대칭을 없앤다.

## 목표

- 온라인 상태를 **모든 앱 인스턴스가 공유**하게 만든다.
- 메시지 파이프라인과 **같은 패턴**(Redis 토픽 publish → 모든 서버의 subscriber가 자기 클라에 재방송)으로 일관성 확보.
- 결과: 백엔드 2대(같은 Redis 공유)여도, 한 서버 클라가 다른 서버 클라의 온라인 상태를 본다.

## 비목표 (이번 범위 밖)

- **죽은 세션 청소(하드 크래시 대비 TTL+하트비트)** — 이번엔 **알려진 한계**로 둔다(결정 (가)). clean disconnect·transport close는 정상 처리. 서버가 갑자기 죽으면 Redis에 유령 세션이 남아 "영원히 온라인"이 될 수 있음 → 다음 확장(항목별 TTL + 주기 갱신).
- 타이핑·방 위치 presence.
- 프론트 변경(프론트는 `/sub/presence` 구독만 하면 되고, 백엔드가 어디서 방송하든 동일 → **프론트 무변경**).

## 핵심 결정

| # | 결정 | 이유 |
|---|------|------|
| 1 | 온라인 상태를 **Redis Hash** `presence:sessions`(field=sessionId, value=memberId)에 저장 | 단일 진실 공급원(SSOT). 멀티세션/멀티서버가 필드 단위로 자연 처리. 인메모리 로직을 그대로 옮김. |
| 2 | presence 전용 **Redis 토픽 `"presence"`** 신설(채팅 `"chatroom"`과 분리) | `RedisSubscriber`가 모든 메시지를 `MessageResponse`로 단정 역직렬화 → 절대 안 섞이게 별도 토픽 + 별도 subscriber. |
| 3 | publish 시 **전체 스냅샷**(`PresenceResponse{onlineMemberIds}`)을 실어 보냄 | subscriber는 그대로 재방송만 하면 됨(메시지 subscriber와 동일하게 단순). publish하는 서버가 Redis에서 최신 목록 읽어 계산. |
| 4 | 죽은 세션 청소는 **MVP에서 제외**(결정 (가)) | 이번 핵심은 "멀티서버 상태 공유". 크래시 청소는 별도 관심사. |

## 데이터 모델 (Redis)

```
presence:sessions   (Hash)
  {sessionId} -> {memberId}
```
- **온라인 memberId 집합** = `HVALS presence:sessions` 의 distinct.
- 한 멤버 여러 세션(여러 탭/여러 서버) → 필드 여러 개 → 마지막 필드 제거 시 오프라인.

브로드캐스트 페이로드(`/sub/presence`, 기존과 동일):
```json
{ "onlineMemberIds": [1, 5, 8] }
```

## 흐름

```
Bob 접속 (예: B서버)
  1) WebSocketEventListener.onConnected
       → PresenceRegistry.connect(sessionId, memberId)  // Redis HSET
  2) 온라인 스냅샷 계산: registry.getOnlineMemberIds()   // Redis HVALS
  3) PresencePublisher.publish(PresenceResponse)         // Redis 토픽 "presence"에 publish
       → A,B 모든 서버의 PresenceRedisSubscriber 수신
       → 각자 messagingTemplate.convertAndSend("/sub/presence", snapshot)  // 자기 서버 클라에
  → Alice(A서버)도 Bob 온라인 수신 ✅

Bob 종료 → onDisconnected → HDEL → 스냅샷 재계산 → publish → 전 서버 재방송
presence 구독(late-join) → onSubscribe(dest=/sub/presence) → 현재 스냅샷 publish
```

핵심: `WebSocketEventListener`는 이제 `SimpMessagingTemplate.convertAndSend`를 **직접 호출하지 않는다**. 대신 Redis에 publish하고, 실제 방송은 `PresenceRedisSubscriber`가 한다 — 메시지 컨트롤러(`ChatMessageController` → `RedisPublisher`)와 동일한 모양.

## 변경/신규 파일

**변경**
- `presence/PresenceRegistry` — `ConcurrentHashMap` → `RedisTemplate` 기반 Hash 연산.
  - `connect(sessionId, memberId)` = `opsForHash().put("presence:sessions", sessionId, memberId)`
  - `disconnect(sessionId)` = 값 조회 후 `delete`, 지운 memberId 반환
  - `getOnlineMemberIds()` = `opsForHash().values(...)` distinct → `Set<Long>`
  - 주의: Redis Hash 값 직렬화(현 `RedisTemplate` value serializer = `GenericJacksonJsonRedisSerializer`)로 `Long`이 들어가고 나올 때 타입 보존/캐스팅 처리. 메시지 경로가 이미 이 template로 동작하므로 동일 방식 재사용.
- `redis/RedisPublisher` — presence 토픽 주입(`@Qualifier`) + **presence 전용 메서드 `publishPresence(PresenceResponse)`** 추가. 기존 `publish(MessageResponse)`는 시그니처·동작 그대로(메시지 경로 무변경, blast radius 최소). `ChatMessageController` 안 건드림.
- `redis/RedisConfig` — `presence` `ChannelTopic` 빈 추가(`@Qualifier`로 `chatroom`과 구분) + `RedisMessageListenerContainer`에 `PresenceRedisSubscriber` 등록.
- `presence/WebSocketEventListener` — 방송을 `SimpMessagingTemplate` 직접 → Redis publish(presence 토픽)로 변경.

**신규**
- `redis/PresenceRedisSubscriber` (`MessageListener`) — `"presence"` 메시지를 `PresenceResponse`로 역직렬화 → `messagingTemplate.convertAndSend("/sub/presence", ...)`. (`RedisSubscriber`와 대칭 구조)

**무변경**
- 프론트 전체(`/sub/presence` 구독은 동일).
- 메시지 파이프라인(`ChatMessageController`, `RedisSubscriber`, `chatroom` 토픽).
- `PresenceResponse` DTO(그대로 재사용).

## 검증

1. **백엔드 컴파일/기존 테스트**: `./gradlew build`. (CI는 라이브 Redis 없음 → Redis 필요한 테스트 금지.)
2. **PresenceRegistry 단위 테스트**: `RedisTemplate`/`HashOperations`를 **Mockito로 목킹**해 올바른 Redis 연산(HSET/HDEL/HVALS)이 호출되는지 검증(CI-safe, 라이브 Redis 불필요). 실제 동작 검증은 3번 E2E가 담당.
3. **멀티서버 E2E (이 작업의 하이라이트, 수동)**:
   - 백엔드 **2개 인스턴스**(8080·8081)를 **같은 Redis + 같은 DB**에 물려 기동.
   - WS 클라 2개: 하나는 8080, 하나는 8081에 접속·`/sub/presence` 구독.
   - **8080 클라가 8081 클라의 온라인/오프라인을 수신**하면 통과(= 멀티서버 상태 공유 증명).
   - 대조: 예전 인메모리 방식이면 서로 못 봤을 상황.

## 배포 영향

- **DB 스키마 변경 없음**(상태는 Redis). `ddl-auto: validate` 무관, 마이그레이션 불필요.
- Redis는 이미 인프라에 있음(메시지용). presence용 토픽/키만 추가.
- 단일 인스턴스 운영에선 **동작 동일**(눈에 보이는 변화 없음). 이득은 멀티 인스턴스로 확장할 때 발현.

## 향후 확장

- **죽은 세션 청소**: 세션 필드 TTL + 주기적 하트비트 갱신(하드 크래시 유령 세션 제거).
- 방 위치 / 타이핑 presence.
