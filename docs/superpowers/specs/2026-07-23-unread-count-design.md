# 실시간 안읽음 카운트 + 안읽음부터 보기 — 설계

- 날짜: 2026-07-23
- 브랜치: `feat/unread-count` (develop 분기, Flyway 포함)
- 범위: 방 목록에 **안읽음 카운트 배지**(실시간), 방 입장 시 **첫 안읽음 메시지로 스크롤 + "여기부터 안읽음" 구분선**.
- 제외: per-message 읽음표시(카톡 "1"), 안읽음 요약 알림 등.

## 목표

방 목록(랜딩)에서 각 방의 안 읽은 메시지 수를 **실시간으로** 보고(다른 방에 새 메시지 오면 배지가 live로 오름), 안읽음 있는 방에 들어가면 **처음 못 본 메시지부터** 이어 볼 수 있게 한다.

## 1. 데이터 모델

- `chatroom_members`에 **`last_read_message_id BIGINT NULL`** 추가 → **Flyway `V2__add_chatroom_members_last_read.sql`** (첫 forward 마이그레이션).
- **방 가입(입장) 시** `last_read_message_id = 그 방의 최신 메시지 id`(없으면 null)로 세팅 → 새로 낀 방의 과거가 안읽음으로 뜨지 않음(0에서 시작).
- **안읽음 정의**: `count(messages m WHERE m.chatroom_id = :room AND m.id > :lastRead AND m.member_id != :me AND m.deleted = false)`. 내 메시지·삭제 메시지 제외. `lastRead`가 null이면 그 방 전체 대상.

## 2. 백엔드

- **초기 조회** `GET /api/chatrooms/unread` → 내가 낀 방들의 `[{ chatroomId, unreadCount, lastReadMessageId }]`. (`lastReadMessageId`는 프론트가 "여기부터 안읽음" 경계를 그리는 데 필요.)
  - 안읽음 카운트는 방 수만큼 세지 않고 **한 번의 그룹 집계 쿼리**로(N+1 방지).
- **읽음 처리** `POST /api/chatrooms/{id}/read` → 그 방의 내 `last_read_message_id`를 최신 메시지 id로 갱신. (방 입장 시, 그리고 보는 중 새 메시지 도착 시 호출.)
- **실시간 fan-out**: 메시지 생성 → 기존 Redis 방송을 받은 **각 서버**가 그 방 멤버(보낸 사람 제외) 중 **자기 서버에 붙은 세션**에게 `convertAndSendToUser(memberId, "/queue/unread", { chatroomId, messageId })` 전송.
  - 모든 서버가 Redis 메시지를 받아 각자 로컬 라우팅 → **멀티서버 안전**([[deferred-private-rooms]]와 무관, presence 패턴과 동일 철학).
  - Principal(STOMP 인증)의 사용자 식별자 = memberId 기준. `convertAndSendToUser`의 user 값이 memberId 문자열인지 플랜 단계에서 확인.
  - 방 멤버 조회는 메시지당 1회(작은 방 전제, 필요 시 캐시).

## 3. 프론트

- 연결 시 **`/user/queue/unread` 상시 구독**(현재 방과 무관). stomp.ts에 사용자 대상 구독 추가.
- 상태 `unread: Record<roomId, number>` — `GET /api/chatrooms/unread`로 초기화, `lastReadMessageId`도 방별로 보관.
- **이벤트 `{ chatroomId }` 수신**: 지금 보는 방이 아니면 `unread[chatroomId]++`.
- **방 입장**: `POST .../read` 호출 + `unread[roomId] = 0`. 보는 중 도착 메시지는 읽음 유지(그 방 이벤트 무시 + 필요 시 mark-read).
- **랜딩(ChannelLanding)**: 우표에 `unread[roomId] > 0`이면 **배지(숫자)**.

## 3.5 안읽음부터 보기

- 방 입장 시 **맨 아래 대신 "첫 안읽음"으로 스크롤** + 그 위에 **"여기부터 안읽음" 구분선**.
- "첫 안읽음" = `id > lastReadMessageId` 중 가장 오래된 메시지.
- **구분선은 입장 시점 경계로 고정** — 입장하며 mark-read로 DB는 갱신되지만, 그 세션 동안 구분선은 그대로(카톡과 동일). 프론트가 입장 시 `lastReadMessageId`를 스냅샷.
- **페이지네이션 연동**(새 로딩 모드 없이 기존 것 재사용):
  - 첫 안읽음이 **로드된 최신 30개 안**이면 → 그 메시지로 스크롤 + 구분선. (대부분)
  - 안읽음이 30개 초과라 경계가 **로드 범위 밖**이면 → 로드된 것 중 맨 위(가장 오래된 것)에 위치, 위로 스크롤(이전 페이지 로드)하다 경계 만나면 구분선.

## 4. 엣지케이스

- 안 낀 방: `last_read` 없음 → `unread` 미포함 → 배지 없음(입장하면 그때부터 추적).
- 내 메시지·삭제 메시지: 안읽음 카운트에서 제외.
- 현재 보는 방: 항상 0 유지(그 방 이벤트 무시).
- 안읽음이 없으면: 입장 시 기존대로 맨 아래로(구분선 없음).

## 5. 검증 (실측)

- **백엔드**: `@SpringBootTest`(H2) — 안읽음 쿼리(경계 `> lastRead`, 내메시지/삭제 제외), `read` 갱신, 가입 시 last_read 세팅, unread 응답 형태. `./gradlew test`.
- **Flyway V2**: 로컬 실제 MySQL로 fresh(V1+V2)·forward(기존→V2 적용) 부팅 검증(도입 편과 동일 방식).
- **프론트**: 격리 하니스 — unread 이벤트→배지 증가(현재 방 제외), 입장→배지 리셋, "여기부터 안읽음" 구분선·첫 안읽음 스크롤. `tsc`·`vite build`.

## 6. 의존성·브랜치

- **Flyway는 develop에 병합 완료(PR #57)** → 이 브랜치에 포함. V2 마이그레이션 바로 가능.
- 브랜치 `feat/unread-count` → develop PR.
- 배포 시 Flyway가 V2를 자동 적용(수동 ALTER 없음 — Flyway 도입 효과).

## 7. 구현 중 결정·편차 (2026-07-24)

설계 시점에 몰랐거나 구현/검증 중 드러나 바로잡은 것들.

### 7.1 STOMP 브로커에 `/queue` 등록 (필수 선행조건)
- §2가 "`convertAndSendToUser`의 user 값 확인"으로 남겨둔 부분을 확정: **Principal 이름 = email**. CONNECT 인터셉터가 `setUser(UsernamePasswordAuthenticationToken(CustomUserDetails))`를 넣고, `AbstractAuthenticationToken.getName()`이 `getUsername()`(=email)을 반환한다.
- **그러나** `WebSocketConfig`가 `enableSimpleBroker("/sub")`로 `/sub`만 등록하고 있었다. `convertAndSendToUser`는 유저 목적지를 `/queue/...`로 리졸브하므로, `/queue` 미등록 상태에선 **구독·전송이 모두 조용히 드롭**된다(예외 없음). 코드베이스에 `convertAndSendToUser` 사용처가 없었고 프론트에도 `/user` 구독이 없어 한 번도 검증된 적 없는 경로였다.
- → `enableSimpleBroker("/sub", "/queue")`로 변경. 기존 `/sub` 브로드캐스트(채팅·presence·typing)와 `/pub` 앱 프리픽스는 그대로.
- 대안이던 "`/sub/users/{email}/unread` 개인 토픽" 방식은 구독 인가 검사가 없어 타인이 남의 토픽을 구독할 수 있어 탈락. 스프링 유저목적지는 인증된 본인 세션에만 배달된다.

### 7.2 수정·삭제 재전파는 안읽음 fan-out 제외
- `RedisSubscriber`는 `"chatroom"` 토픽 단일 리스너인데 publish 지점이 3곳(STOMP 신규 발신 / REST 메시지 수정 / REST 메시지 삭제)이다. fan-out을 무조건 걸면 **수정 1회마다 상대 배지 +1**, **삭제 시 배지는 +1인데 서버 집계는 -1**(집계가 `deleted=false`만 셈)로 반대 방향 어긋남이 생긴다.
- → 신규 메시지일 때만 fan-out: `if (editedAt == null && !deleted)`. `/sub` 방 브로드캐스트는 수정·삭제에도 계속 전송(실시간 반영 유지).
- 아울러 fan-out은 전용 try/catch + 멤버별 격리로 분리(기존 단일 catch가 "역직렬화 실패"로 오기록하고, 루프 중 예외 시 나머지 멤버 전송이 통째로 누락됐음).

### 7.3 구분선 경계: "입장 시점 스냅샷 + 천장"
- §3.5의 "입장 시점 경계 고정"을 프론트에서 **ChatArea가 입장 시 `{boundary, ceiling}`을 ref로 캡처**하는 방식으로 구현. `ceiling` = 입장 순간 로드된 최신 메시지 id.
- 천장이 없으면 **보고 있는 방에 실시간 도착한 메시지 위에 구분선이 생기고 사라지지 않는다**(경계보다 id가 크고 직전 메시지가 경계 이하인 순간이 생기므로). 천장으로 "입장 시점에 존재하던 메시지"만 후보로 제한.
- 로컬 경계(`roomLastRead`)는 입장 시, 그리고 **보는 중 도착한 메시지마다** 전진시킨다 → 나갔다 재입장해도 이미 본 메시지에 구분선이 다시 뜨지 않음. 현재 열린 화면은 ref 스냅샷으로 고정돼 있어 영향 없음.

### 7.4 보는 중 읽음 처리
- §2가 요구한 "보는 중 새 메시지 도착 시 mark-read"를 구현(1초 스로틀). 이게 없으면 방에 머무르며 읽은 메시지가 새로고침 후 안읽음으로 되살아난다.

### 7.5 안읽음 조회 실패 격리
- 초기 `getUnreadCounts` 실패가 `getMe`/`refreshRooms`와 같은 catch에 걸려 `clearSession()`(로그아웃)까지 유발하던 것을 자체 try/catch로 분리(실패 시 배지만 없고 앱은 정상 동작). STOMP 재연결 시 카운트 재조회도 추가.

### 7.6 검증 전략 변경
- 프론트 격리 하니스(mock 스크린샷) 대신 **실 백엔드+Redis+MySQL+브라우저 E2E**로 검증. 실측 항목: 초기 배지, STOMP 실시간 배지 증가, 수정·삭제 시 배지 불변, 구분선 위치/개수, 보는 중 도착 시 구분선 없음, 재입장 클린, 입장 시 배지 0 및 DB 포인터 전진.

### 7.7 알려진 한계 (이번 범위 밖)
- **REST 폴백 발신은 실시간 전파 없음(기존)**: `POST /api/chatrooms/{id}/messages`는 `redisPublisher.publish`를 호출하지 않는다(STOMP 발신과 REST 수정/삭제만 호출). STOMP 끊김 시 폴백 발신은 원래도 실시간 브로드캐스트가 없었고, 안읽음 이벤트도 가지 않는다. 카운트는 DB 집계라 다음 로드 시 정확.
- **삭제 시 배지 실시간 감소 없음**: 안읽은 메시지가 삭제되면 서버 집계는 줄지만 배지는 그대로(부풀지는 않음). 입장·재조회 시 보정된다.
- 성능: 메시지당 방 멤버 조회 1회 + Redis 리스너의 메시지당 스레드. 작은 방 전제이며, 커지면 멤버 캐시·리스너 스레드풀 도입 검토.
