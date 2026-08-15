# 메시지 커서 페이지네이션 + 방 멤버십 검사 — 설계·검증

- 날짜: 2026-07-22
- 브랜치: `feat/message-pagination` (develop 분기)
- 범위: 채팅 히스토리 조회를 전체 로드 → **커서 기반 페이지네이션**으로 전환. 겸사겸사 조회 시 **방 멤버십 검사** 추가 + 페이지 내 **N+1 제거**.

## 문제

- `GET /api/chatrooms/{id}/messages`가 방의 **전체 메시지**를 한 번에 반환(`findByChatRoomOrderById`). 방이 커지면 응답·렌더가 선형으로 폭증.
- 조회 엔드포인트에 **방 멤버십 검사 없음** — 로그인만 하면 임의 방 히스토리 열람 가능(경미한 정보 누수).
- `MessageResponse.from()`이 LAZY `member.getNickname()` 접근 → 리스트 매핑 시 **N+1**.

## 결정

- **커서 = `Message.id`(auto-increment) keyset.** `createdAt`은 동시각 충돌 위험 → id가 유일·단조라 안전.
- **API**: `GET .../messages?before={id}&limit={n}` — `before` 없으면 최신 페이지, 있으면 그 id **미만(exclusive)**. `limit` 기본 30, 서버에서 1~50 클램프.
- **응답 형태 변경(breaking, 의도적)**: 배열 → `{ "messages": [...id 오름차순], "hasMore": boolean }`. 벌거벗은 배열엔 "더 있음" 플래그를 담을 곳이 없어서. 프론트·백엔드 동일 레포·동시 배포라 안전.
- **N+1 제거**: 페이지 쿼리에 `JOIN FETCH m.member`. (member는 to-one이라 `Pageable`과 함께 써도 DB 페이징 정상 — 컬렉션 fetch가 아님.)
- **멤버십 검사**: 기존 `MessageService.create()`의 `existsByMemberAndChatRoom` 가드를 조회에도 동일 적용 → 비멤버는 `NOT_JOINED_ROOM(403)`. 정상 흐름은 방 입장 시 `joinChatRoom`을 먼저 하므로 영향 없음(비멤버 직접 접근만 차단).

## 구현 (최소 변경)

**백엔드**
- `MessageRepository`: `findLatestByChatRoom(room, Pageable)` / `findOlderByChatRoom(room, before, Pageable)` — 둘 다 `JOIN FETCH m.member ... ORDER BY m.id DESC`.
- `MessageService.getMessages(chatroomId, memberId, before, limit)`: 멤버십 검사 → `limit+1` 조회로 `hasMore` 판정 → `limit`로 잘라 오름차순 반전 → `record MessagePage(messages, hasMore)`.
- `MessagePageResponse`(신규 DTO), `MessageController.getMessages`가 `@AuthenticationPrincipal` + `before`/`limit`(1~50 클램프) 받아 매핑.
- 기존 `getAllChatRoomMessages`는 유지(기존 테스트용, 컨트롤러 미사용).

**프론트**
- `getMessages(token, room, before?, limit=30)` → `{messages, hasMore}`.
- `App`: 방별 `pageState{oldestId, hasMore, loading}`. 초기 = 최신 페이지(맨아래 유지). `loadOlderMessages(room)`가 `before=oldestId`로 이전 페이지를 **prepend**(before exclusive라 중복 없음).
- `ChatArea`: 상단 근처(`scrollTop<80`, 초기 하단스크롤 완료 후) → `onLoadOlder`. prepend 시 `useLayoutEffect`로 `scrollHeight` 증가분만큼 `scrollTop` 보정 → **점프 없음**. `hasMore=false`면 "채널 시작" 배너 노출·추가 로드 중단. `loadingOlder` 중 상단 인디케이터.

## 검증 (실측)

**백엔드** — `@SpringBootTest`(H2) 통합테스트 `MessagePaginationTest` 신규, `./gradlew test` 전체 통과:
- 70개 생성 → 최신 30(41~70 오름차순, hasMore=true) → before=41id로 다음 30(11~40, 41 미포함=exclusive) → 마지막 10(1~10, hasMore=false).
- 비멤버 조회 → `CustomException(NOT_JOINED_ROOM)`.
- fetch join으로 `member.getNickname()` 접근 정상.

**프론트** — 격리 하니스로 `ChatArea`를 실제 렌더, App의 loadOlder를 모사해 E2E:
- 초기 30개·맨아래·배너 숨김 → 위로 스크롤 시 이전 30 prepend, **기준 메시지 위치 드리프트 0px**(scrollTop이 콘텐츠 증가분만큼 정확히 보정) → 재스크롤로 마지막 10 로드·위치 보존 → 시작 도달 시 배너 등장·**추가 로드 없음(무한루프 없음)**.
- `tsc`·`vite build` exit 0.

## 범위 밖(짚음)

- DB 인덱스: `messages(chatroom_id, id)` 복합 인덱스가 있으면 keyset 스캔이 최적. 현재 스키마 확인·추가는 별건(운영 마이그레이션).
- 실시간(STOMP)·전송 경로는 무변경 — 페이지네이션은 히스토리 로드에만 관여.
