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
