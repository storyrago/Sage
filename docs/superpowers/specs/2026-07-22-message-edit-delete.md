# 메시지 수정 / 삭제 — 설계·검증

- 날짜: 2026-07-22
- 브랜치: `feat/message-edit-delete` (develop 분기)
- 범위: 보낸 메시지의 **수정**과 **소프트 삭제**. 작성자 권한 검사 + 실시간 반영. 백엔드+프론트 세로 슬라이스.

## ⚠️ 배포 전 필수: DB 마이그레이션

엔티티에 컬럼 2개(`edited_at`, `deleted`)가 추가된다. 프로드는 `spring.jpa.hibernate.ddl-auto: validate`라, **DB에 이 컬럼이 없으면 앱이 부팅되지 않는다.** 따라서 **배포(develop 머지) 전에** RDS에 아래를 먼저 실행해야 한다:

```sql
ALTER TABLE messages
  ADD COLUMN edited_at datetime(6) NULL,
  ADD COLUMN deleted   bit NOT NULL DEFAULT 0;
```

(테스트는 H2 `create-drop`이라 컬럼이 자동 생성 → CI는 초록이지만, 그게 프로드 스키마를 보장하지 않는다. 근본 대책은 Flyway 도입 — 별건.)

## 설계 핵심 — 기존 방송 파이프라인 재사용

`RedisPublisher.publish(MessageResponse)` → 전 클라 `/sub/chatrooms/{id}` 방송. 수정/삭제도 **결과 MessageResponse를 그대로 publish**하면, 클라가 **같은 id의 메시지를 교체**해 실시간 반영된다. 새 이벤트 타입 불필요.

- 프론트 실시간 수신은 **upsert**: id 있으면 제자리 교체(수정/삭제), 없고 방의 최신 id보다 크면 append(새 메시지), 없고 옛 id면 무시(로드 안 된 옛 메시지의 수정/삭제 — 페이지네이션으로 최신 상태를 받음).

## 구현

**백엔드**
- `Message`: `editedAt`·`deleted` 필드 + `edit(content)`(content+editedAt) / `softDelete()`(deleted=true, content·imageUrl 비움).
- `MessageService.update/delete(messageId, memberId, ...)`: 작성자 아니면 `NOT_MESSAGE_OWNER(403)`(스텁으로 있던 코드 사용). 수정은 삭제된 메시지 거부·빈 content 거부.
- `MessageController`: `PATCH`/`DELETE .../{messageId}` → 서비스 호출 후 `RedisPublisher.publish`로 실시간 전파. `MessageUpdateRequest{content}`.
- `MessageResponse`에 `editedAt`·`deleted` 추가(삭제 시 서버가 content 비운 상태로 감).

**프론트**
- `api.ts`: `updateMessage`(PATCH)·`deleteMessage`(DELETE), `BackendMessage`/`toMessage`에 `editedAt→edited`·`deleted` 매핑.
- `App`: `onMessage` upsert, `handleEditMessage/DeleteMessage`(REST 호출 + 낙관적 반영).
- `ChatArea`: 내 메시지 hover에 수정/삭제 버튼(삭제된 메시지는 액션 없음), 수정은 입력창+"수정 중" 배너 재사용, 삭제 렌더("삭제된 메시지입니다")·"· 수정됨" 배지.

## 검증 (실측)

- **백엔드** `MessageEditDeleteTest`(H2) + `./gradlew test` 전체 통과: 남이 수정/삭제→`NOT_MESSAGE_OWNER`, 작성자 수정→content 변경+editedAt, 삭제→deleted+content 비움.
- **프론트** 격리 하니스로 실제 `ChatArea` 렌더 후:
  - 권한 UI: 내 메시지=답장/수정/삭제, 남 메시지=답장만.
  - **upsert** 4케이스: 제자리 교체(수정 텍스트+"수정됨"), 최신 append, 옛 id 무시, 삭제 렌더.
  - 인터랙션: 수정 버튼→"수정 중" 배너+입력 프리필→제출 시 텍스트 갱신·"수정됨"·배너 해제; 삭제 버튼→"삭제된 메시지입니다"·액션 사라짐.
  - `tsc`·`vite build` exit 0.

## 범위 밖(짚음)

- 이미지 전용 메시지 "수정"은 빈 content 가드로 사실상 텍스트 편집만 유효.
- 하드 삭제·수정 이력(edit history)은 범위 아님.
