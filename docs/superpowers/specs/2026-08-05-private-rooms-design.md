# 비공개방과 방장 권한 설계

2026-08-05

## 1. 목표

방에 **주인**과 **잠금**을 도입한다. 지금은 로그인만 하면 모든 방이 보이고 아무 방이나 들어갈 수 있다. 인증은 있으나 인가가 없다.

닫으려는 구멍 둘:

- `GET /api/chatrooms`가 `findAll()`로 전체 방을 반환한다
- `POST /api/chatrooms/{id}/members`(join)에 중복 검사만 있고 권한 검사가 없다

메시지 읽기·쓰기는 이미 `RoomAccess.isMember`로 막혀 있다. 그 검사가 비로소 진짜 방어선이 되게 하는 작업이다.

## 2. 결정 사항

| 항목 | 결정 |
|---|---|
| 주인 | 방 생성자. 운영 권한을 전부 가진다 |
| 비공개방 노출 | **목록에 보인다.** 비멤버에게는 이름과 잠김 표시만 |
| 입장 수단 | **서버가 생성한 랜덤 코드.** 사람이 정하지 않는다 |
| 방장 권한 | 강퇴 · 코드 재발급 · 공개↔비공개 전환 · 방 삭제 |
| 강퇴 실효성 | **차단 목록 테이블.** 멤버십 행 삭제만으로는 즉시 재입장된다 |
| 방 삭제 | **소프트**(`deleted_at`) |
| 마이그레이션 | Flyway V7 |

### 2.1 왜 목록에 보이는가

슬랙의 private 채널은 목록에서 아예 사라진다. 우리는 카카오 오픈채팅 비밀방에 가깝게, 잠긴 방이 목록에 보이고 코드를 넣으면 열리는 쪽을 택한다.

**대가**: 방 이름이 전원에게 노출된다. "퇴사 논의" 같은 이름이면 안을 못 봐도 존재만으로 정보가 샌다. 완전 숨김이 필요해지면 `is_listed` 같은 축을 하나 더 두는 별도 작업이 된다.

### 2.2 왜 사람이 정한 비밀번호가 아닌가

사람이 정한 방 비번은 짧고 추측 가능하며(`1234`, 방 이름), 다른 서비스 비번과 겹치기 쉽다. 그러면 BCrypt 해시 저장, 시도 제한, 최소 길이 검증이 전부 따라온다.

서버 랜덤 코드는 그 셋을 동시에 없앤다. 추측이 불가능하고 남의 비번을 베낀 것이 아니므로 평문 저장이 정당하며, 방장이 언제든 조회하고 재발급할 수 있다.

**조건 1: 코드 길이가 곧 보안이다.** 혼동 문자(`0/O`, `1/l/I`)를 뺀 32자 알파벳으로 **12자**를 쓴다. 약 60비트다. 6자로 줄이면 브루트포스 문제가 그대로 돌아오고 그때는 시도 제한이 다시 필요해진다. 난수원은 `SecureRandom`이다.

**조건 2: 코드는 요청 본문과 응답 본문에만 싣는다.** URL 경로·쿼리스트링·리다이렉트 `Location`·로그에 절대 넣지 않는다. nginx가 `$request`와 `$http_referer`를 액세스 로그에 남기고 그 로그는 awslogs 드라이버로 CloudWatch `/sage/web`에 적재된다. 이 레포는 같은 이유로 OAuth 코드의 URL 사용을 이미 금지해 두었다. 초대 링크(`?code=...`)를 만드는 순간 코드가 로그와 Referer로 새고 되돌릴 방법이 없다. 코드 충돌 재시도는 예외 메시지를 로깅하지 않고 조용히 재생성한다.

### 2.3 왜 소프트 삭제인가

업계 표준이 소프트다. Slack은 아카이브가 기본이고 삭제는 공개 API에 메서드조차 없다(Enterprise Grid 전용 admin API). Teams는 채널 21일·팀 30일 복원 창을 두고 "아카이브를 먼저 하고 삭제는 확신이 설 때까지 미루라"고 문서에 권고한다. Notion은 휴지통 30일 → 접근 불가 보존 30일 → purge의 3단계다. 하드는 Discord·Telegram뿐이다. 공통점은 **되돌릴 수 없는 동작에 반드시 마찰을 하나 넣는다**는 것이다 — 유예기간이든 권한 격리든.

비용도 소프트가 낮다. 하드 삭제는 코드량이 비슷한데(10~12곳) 테스트가 못 잡는 위험이 붙는다:

- `messages.reply_to_id` 자기참조 FK에 `ON DELETE`가 없어, 방 메시지 벌크 DELETE는 부모가 자식보다 먼저 지워지는 순서에서 FK 위반으로 터진다
- **그 실패는 `./gradlew test`로 재현되지 않는다.** 테스트는 H2 create-drop + Flyway 비활성이고 운영만 MySQL이다
- `ImageCleanupListener`는 `@Async`가 아니고 `ImageReferences.isReferenced`가 URL당 `content LIKE '%url%'` 풀스캔을 돈다. 이미지 30장짜리 방을 지우면 90쿼리 + S3 왕복 30번이 커밋 직후 요청 스레드에서 동기로 돈다

**정직하게**: 복원 UI가 없는 소프트 삭제는 "데이터를 남겨두는 하드 삭제"다. 사용자에게 보이는 동작은 하드와 같다. 값어치는 셋이다 — 실수로 지웠을 때 DB에서 되살릴 수 있고, FK 순서·S3 정리 작업이 통째로 사라지고, 나중에 "N일 후 purge"를 얹을 자리가 생긴다. 반대 방향은 없다. 복원 UI와 purge 배치는 이번 범위 밖이다.

## 3. 데이터 모델 — Flyway V7

```sql
ALTER TABLE chatrooms
  ADD COLUMN created_by  BIGINT       NULL,
  ADD COLUMN is_private  BOOLEAN      NOT NULL DEFAULT FALSE,
  ADD COLUMN invite_code VARCHAR(12)  NULL,
  ADD COLUMN deleted_at  DATETIME(6)  NULL;

ALTER TABLE chatrooms
  ADD CONSTRAINT uk_chatrooms_invite_code UNIQUE (invite_code),
  ADD CONSTRAINT fk_chatrooms_created_by FOREIGN KEY (created_by) REFERENCES members (id);

CREATE TABLE chatroom_bans (
  chatroom_id BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  banned_at   DATETIME(6) NOT NULL,
  PRIMARY KEY (chatroom_id, member_id),
  CONSTRAINT fk_bans_chatroom FOREIGN KEY (chatroom_id) REFERENCES chatrooms (id),
  CONSTRAINT fk_bans_member   FOREIGN KEY (member_id)   REFERENCES members (id)
);
```

### 3.1 잠금은 `is_private`이 정한다

`invite_code`가 있으면 잠김으로 삼는 단일 컬럼 설계를 검토했으나 버렸다. **주인이 탈퇴한 방**을 표현할 수 없기 때문이다(§3.3). 세 가지 상태가 필요하다:

| 상태 | `is_private` | `invite_code` | 뜻 |
|---|---|---|---|
| 공개 | `false` | `NULL` | 누구나 입장 |
| 비공개 | `true` | 있음 | 코드로 입장 |
| **동결** | `true` | `NULL` | **아무도 새로 못 들어옴.** 기존 멤버만 유지 |

동결은 카카오 오픈채팅이 방장 부재에 쓰는 상태와 같다. 불일치 상태(`is_private=false`인데 코드가 있음)는 **공개 전환 시 코드를 항상 `NULL`로 지운다**는 규칙으로 막는다. 유출된 옛 코드가 재전환 때 부활하지도 않는다.

### 3.2 `created_by`

nullable이다. 기존 시드 방(공지·잡담)은 주인이 없고, 주인이 탈퇴해도 방은 남아야 한다.

**`ON DELETE SET NULL`을 쓰지 않는다.** 그 제약은 Flyway/MySQL 경로에만 존재하고, 테스트는 H2 `ddl-auto: create-drop` + Flyway 비활성이라 검증되지 않는다. 게다가 Hibernate는 `ON DELETE`를 DDL에 넣지 않으므로 H2에는 평범한 FK만 생겨 **탈퇴 테스트가 참조 무결성 위반으로 깨진다.** 이 레포의 기존 패턴도 DB 제약이 아니라 앱 레벨 정리다(`messageRepository.anonymizeByMember`). 같은 패턴을 따른다 — §3.3.

### 3.3 주인이 탈퇴하면

`MemberService.delete`에 벌크 UPDATE 한 줄을 더한다. 멤버십을 지우기 전에 실행한다.

```
UPDATE ChatRoom c SET c.createdBy = null, c.inviteCode = null, c.isPrivate = <유지>
WHERE c.createdBy = :member
```

즉 **주인을 지우고 코드를 회수하되 잠금은 유지한다.** 결과는 §3.1의 동결 상태다.

이 한 줄이 세 문제를 동시에 푼다:

- H2에서 탈퇴 테스트가 깨지지 않는다(FK 참조가 먼저 끊긴다)
- 유출된 코드로 주인 없는 잠긴 방에 영원히 들어오는 경로가 막힌다
- 남은 멤버의 대화가 지워지지 않는다

**주인 없는 방은 아무도 운영할 수 없다.** 강퇴·재발급·전환·삭제가 전부 거부된다. 시드 방과 탈퇴로 주인을 잃은 방이 여기 해당한다. 되살리려면 방장 위임이 필요한데 범위 밖이다(§11).

## 4. 노출 정책과 DTO 계약

`ChatRoomResponse`가 요청자에 따라 달라지므로 **정적 팩토리 시그니처가 바뀐다.** 이것을 명시하지 않으면 가장 손이 덜 가는 구현(`from`에 `inviteCode`를 그냥 추가)이 **전원에게 모든 방의 코드를 뿌린다.** 비공개 설계 전체가 한 줄로 무너지는 자리다.

- `ChatRoomResponse.from(ChatRoom)` **단일 인자 팩토리는 삭제한다.** 남겨두면 다음 사람이 그걸 집는다
- `from(ChatRoom room, Long requesterId, boolean joined)`로 바꾼다
- `GET /api/chatrooms`와 `POST /api/chatrooms` 둘 다 `@AuthenticationPrincipal`을 받게 바꾼다. 지금은 둘 다 무인자다

| 필드 | 규칙 |
|---|---|
| `locked` | `is_private` |
| `joined` | 요청자가 멤버인가 |
| `owner` | `createdBy != null && createdBy.id == requesterId`. **`createdBy`가 NULL이면 `false`** |
| `inviteCode` | **`owner == true`일 때만 채운다.** 그 외에는 필드 자체를 내보내지 않는다 |

비멤버에게 잠긴 방은 이름·`locked`·`createdAt`만 나간다. 참여자 수·안읽음·마지막 활동은 내보내지 않는다.

`deleted_at`이 채워진 방은 목록에서 제외한다.

**N+1 회피**: `joined`를 방마다 `existsByMemberIdAndChatRoomId`로 계산하면 방 수만큼 쿼리가 돈다. 요청자의 멤버십 방 id를 한 번에 조회해(`SELECT cm.chatRoom.id FROM ChatRoomMember cm WHERE cm.member.id = :id`) 메모리에서 대조한다.

## 5. API

### 5.1 방 생성 — `POST /api/chatrooms`

요청에 `private: boolean`을 더한다. 서버가 하는 일:

1. `created_by`에 요청자를 넣는다
2. `private`이면 `is_private = true`로 두고 코드를 발급한다
3. **요청자를 첫 멤버로 등록한다**

3번이 핵심이다. 지금은 프론트가 생성 후 따로 `join`을 부르는데, 비공개방에서는 그 호출이 코드 없이 거부된다. 서버가 등록하면 프론트의 기존 `join` 호출은 `ALREADY_JOINED_ROOM`을 이미 삼키므로 그대로 두어도 호환된다.

코드 충돌은 `UNIQUE` 제약이 DB에서 막는다. `DataIntegrityViolationException`을 잡아 재생성한다(기존 `join`의 중복 처리와 같은 패턴).

### 5.2 입장 — `POST /api/chatrooms/{id}/members`

기존 엔드포인트에 선택 필드 `inviteCode`를 더한다. 새 엔드포인트를 만들지 않는 이유는 프론트가 **방을 고를 때마다 이 호출을 하기 때문**이다. join은 "가입"이 아니라 "입장"이다.

**`@RequestBody(required = false)`로 받는다.** 현재 이 핸들러에는 `@RequestBody`가 아예 없고 프론트는 본문 없이 POST한다. 기본값(`required = true`)으로 붙이면 `HttpMessageNotReadableException`이 나는데 `GlobalExceptionHandler`에 이 예외의 핸들러가 없어 **500 + 스택트레이스 로깅**이 된다. 새 DTO는 `inviteCode` 하나만 갖는다. **요청자는 언제나 JWT에서만 온다** — 본문의 어떤 필드도 신원으로 쓰지 않는다.

판정 순서:

| 상황 | 결과 |
|---|---|
| 차단 목록에 있음 | `ROOM_BANNED` 403 |
| 이미 멤버 | `ALREADY_JOINED_ROOM` (지금과 동일) |
| 공개방 | 통과 (지금과 동일) |
| 잠김 · 코드 일치 | 통과, 멤버 등록 |
| 잠김 · 코드 불일치/누락 | `INVALID_INVITE_CODE` 403 |
| 잠김 · 코드가 `NULL`(동결) | `INVALID_INVITE_CODE` 403 |

차단 검사가 가장 먼저다. 코드 비교는 `MessageDigest.isEqual`로 한다.

### 5.3 방장 전용

전부 `created_by == 요청자`가 아니면 `NOT_ROOM_OWNER` 403이다. **`created_by`가 `NULL`이면 요청자와 무관하게 `NOT_ROOM_OWNER` 403이다** — 시드 방이 정확히 이 경우다. `room.getCreatedBy().getId().equals(...)` 형태로 쓰면 NULL에서 NPE 500이 나고 스택트레이스가 CloudWatch로 나간다. 레포에 이미 있는 fail-closed 패턴을 그대로 쓴다(`MessageService`의 `message.getMember() == null || !...equals(memberId)`).

| 동작 | 엔드포인트 |
|---|---|
| 코드 재발급 | `POST /api/chatrooms/{id}/invite-code` |
| 공개↔비공개 전환 | `PATCH /api/chatrooms/{id}` — `{ "private": true \| false }` |
| 멤버 강퇴 | `DELETE /api/chatrooms/{id}/members/{memberId}` |
| 차단 해제 | `DELETE /api/chatrooms/{id}/bans/{memberId}` |
| 방 삭제 | `DELETE /api/chatrooms/{id}` |

- **강퇴는 멤버십 행 삭제 + 차단 목록 등록이다.** 삭제만 하면 무의미하다 — 프론트가 방 선택마다 `join`을 부르므로 공개방에서는 우표를 다시 클릭하는 것만으로 즉시 재입장되고, 잠긴 방이어도 쫓겨난 사람이 쓰던 코드가 그대로 유효하다. 차단 목록 없이는 강퇴가 완전한 no-op이다
- **차단 해제 API가 필요하다.** 없으면 실수로 누른 강퇴가 영구가 된다
- 주인 자신은 강퇴할 수 없다
- **주인은 방을 나갈 수 없다.** `leave`가 주인이면 `OWNER_CANNOT_LEAVE` 409다. 나가려면 방을 삭제해야 한다. 단 이 불변식은 `leave` 경로에만 성립한다 — 탈퇴는 §3.3이 처리한다
- **공개→비공개 전환 시 기존 멤버는 유지한다.** 슬랙도 그렇게 한다. 내보내야 하면 강퇴를 쓴다
- **비공개→공개 전환은 코드를 `NULL`로 지운다**

## 6. 인가 판정

**판정을 새로 만들지 않는다.** `RoomAccess.isMember`가 REST와 WebSocket이 **공유하는** 유일한 지점이고, 여기에 조건을 더한다.

```
existsByMemberIdAndChatRoomId(...) AND chatRoom.deletedAt IS NULL
```

이 한 곳으로 STOMP 구독·발행 인가(`WebSocketAuthorizationConfig`), 메시지 생성·조회·수정·삭제(`MessageService`), 멤버 목록(`ChatRoomMemberService`)이 덮인다.

**단 "유일"이 아니다.** `ChatRoomMemberService.leave`와 `markRead`는 `findByMemberAndChatRoom`으로 직접 조회해 `NOT_JOINED_ROOM`을 던진다. 이번 삭제 조건은 두 번째 병목(`getChatRoomById`)에 걸려 우연히 덮이지만, 앞으로 `RoomAccess`에만 얹는 조건은 이 두 경로에 도달하지 않는다.

`getChatRoomById`에도 같은 조건을 넣되(`findByIdAndDeletedAtIsNull`), **`leave`는 예외로 삭제된 방도 조회한다.** 그러지 않으면 삭제된 방의 멤버십 행을 사용자가 영영 지울 수 없어 고아 멤버십이 남는다.

**SEND 거부 사유는 `NOT_JOINED_ROOM` 하나로 고정이다.** `RoomAuthorizationChannelInterceptor`가 코드와 문구를 하드코딩하고, 인가 매니저는 boolean만 돌려주므로 거부 이유(비멤버 / 삭제된 방)를 구분할 정보가 없다. 삭제 사유 전달은 §7의 통지 경로가 담당한다. 인가 판정이 사유를 돌려주게 바꾸는 것은 범위 밖이다.

따로 손대야 하는 지점:

- **`ChatRoomRepository`에 `findByDeletedAtIsNull()`을 추가하고** `getAllChatRooms`가 그걸 부른다. `findAll()`을 `@Query`로 오버라이드하지 않는다 — `findAll(Sort)`·`findAllById`·`count()`가 필터 없이 남아 다음 사람이 그중 하나를 집으면 삭제된 방이 조용히 되살아난다
- `MessageService.update`/`delete`에서 **`requireMember`를 `getMessageById`보다 먼저 호출한다.** 지금 순서로는 비멤버가 응답 코드로 방 소속을 구분할 수 있다(403이면 그 방 메시지, 404면 아님). 메시지 id를 훑으면 잠긴 방의 활동 시점이 샌다. 경로의 `chatroomId`만으로 판정 가능하므로 순서 교체 한 줄이다

**`findMembersByChatRoomId`에는 조건을 넣지 않는다.** §7의 삭제 통지 리스너가 이 쿼리로 회수 대상을 찾기 때문이다. 조건을 넣으면 `AFTER_COMMIT` 시점에 항상 빈 리스트가 되어 **구독 회수와 통지가 통째로 무동작이 된다**(예외도 안 나서 로그로도 안 잡힌다). 삭제된 방의 안읽음 fan-out은 애초에 도달 불가능하다 — `RoomAccess` 조건 때문에 발행 자체가 막히므로 `RedisSubscriber`가 돌 일이 없다.

`findUnreadCountsByMemberId`에는 `chatrooms` 조인을 더한다. 근거는 "안 지워지는 배지"가 아니라 **삭제된 방 정보를 응답에 싣지 않는다**는 노출 최소화다. 프론트는 `channels`에 없는 방의 배지를 렌더하지 않으므로 화면 결함은 없다.

## 7. 삭제·강퇴 통지와 구독 회수

**소프트 삭제만으로는 이미 접속 중인 사람이 안 끊긴다.** STOMP 인가는 SUBSCRIBE/SEND 프레임 시점에만 평가된다.

회수가 필요한 이유는 "메시지가 계속 온다"가 아니다 — §6의 조건 때문에 삭제된 방에는 아무도 발행할 수 없다. 진짜 이유는 **통지가 없으면 사용자가 유령 화면에 남고, 프레즌스 로스터만 계속 돈다**는 것이다.

`RoomDeletedEvent(roomId)`를 추가하고 `SubscriptionRevocationListener`에 `@TransactionalEventListener(AFTER_COMMIT)` 핸들러를 붙인다. 리스너가 `findMembersByChatRoomId`로 대상을 조회해 각자 회수한다(소프트 삭제라 커밋 후에도 멤버십 행이 남는다).

### 7.1 사유를 끝까지 전달해야 한다

`RoomLeftEvent(memberId, roomId)`에는 사유 필드가 없고, 리스너는 자진 퇴장과 강퇴를 구분할 수단이 없다. **이벤트를 그대로 재사용하면 강퇴당한 사람도 `ROOM_MEMBERSHIP_REVOKED`를 받고, 프론트가 그 코드에서 토스트를 의도적으로 억제하므로 아무 설명 없이 튕긴다.** 이 절이 피하려던 바로 그 증상이다.

시그니처를 바꾼다:

- `RoomLeftEvent(memberId, roomId, reason)` — `reason`은 `LEFT | KICKED` enum
- `RoomSubscriptionRevoker.revokeRoom(memberId, roomId, reason)`
- `revokeAll(memberId, reason)` — 탈퇴 경로도 같은 변경 대상이다
- `private notifyRevoked(memberId, roomId, reason)`

`ErrorCode`에 둘을 더한다:

- `ROOM_DELETED` — "방이 삭제되었어요."
- `ROOM_KICKED` — "방에서 내보내졌어요."

**알려진 한계**: 구독 회수는 단일 인스턴스 전제다. `enableSimpleBroker` + 로컬 `SimpUserRegistry`라서 다른 인스턴스에 붙은 세션은 회수되지 않는다. `PresenceRegistry`의 기존 `ponytail:` 주석이 같은 전제를 기록해 두었다. 현재 배포는 단일 인스턴스다.

## 8. 프론트

- **생성 모달** — 비공개 체크박스. 체크하면 생성 후 코드를 보여준다
- **잠긴 우표** — `locked && !joined`이면 자물쇠 표시. 클릭하면 입장 대신 코드 입력
- **방장 패널** — `owner`일 때만. 코드 보기·재발급, 멤버 강퇴, 차단 해제, 공개↔비공개, 방 삭제
- **`ROOM_DELETED`·`ROOM_KICKED` 처리** — `onAuthzError`에 분기를 더한다. `joinedRoomsRef`·`unread`·`roomLastRead`에서 그 방 키를 지우고, **`channels`에서도 제거한다.** 지금은 `channels`를 건드리지 않아 유령 우표가 남는다
- **`refreshRooms` 호출 지점 추가** — 현재 부트스트랩과 방 생성 직후 둘뿐이다. 랜딩 복귀(`onGoHome`)와 WS 재연결에 추가한다. 오프라인이었던 사용자는 통지를 못 받으므로 목록 갱신이 유일한 경로다
- **`selectedChannelId` 무효화** — 목록만 갱신하고 선택을 비우지 않으면 헤더가 영원히 "메시지를 불러오는 중입니다."로 남는다. 오류가 로딩으로 위장된다

## 9. 단계 분할

한 PR로는 크다. 스펙은 하나지만 플랜과 PR을 둘로 나눈다.

**1단계 — 인가 기반**
V7 마이그레이션 전체(`chatroom_bans` 포함), `created_by`·`is_private`·`invite_code`, DTO 계약 변경(§4), 생성자 자동 등록, 코드 입장, `RoomAccess` 조건, `MemberService.delete`의 소유 방 처리(§3.3). 프론트는 생성 모달 체크박스·잠긴 우표·코드 입력.

**2단계 — 방장 운영**
강퇴 + 차단 목록, 차단 해제, 코드 재발급, 공개↔비공개 전환, 소프트 삭제, `RoomDeletedEvent`, 사유 전달 시그니처 변경(§7.1), 새 ErrorCode, 프론트 방장 패널과 통지 처리.

**1단계만 배포해도 앱은 온전하다.** 방을 만들고 잠그고 코드로 들어가는 흐름이 완결되며, 운영 기능이 없을 뿐이다. `deleted_at`과 `chatroom_bans`는 1단계에서 만들되 쓰지 않는다 — 마이그레이션을 두 번 나눌 이유가 없다.

## 10. 검증 계획

- **인가** — 코드 일치/불일치/누락 입장, 동결 방(`is_private=true`, 코드 `NULL`) 입장 거부, 차단된 사람의 재입장 거부, 비멤버의 잠긴 방 응답에 **`inviteCode` 키가 없음**, 주인 아닌 사람의 방장 API 4종 거부
- **`created_by IS NULL` 방** — 시드 방(공지·잡담)에 방장 API 4종 호출 → 전부 **403이고 500이 아님**
- **탈퇴** — 방을 소유한 회원이 탈퇴하면 방이 남고, `created_by`·`invite_code`가 `NULL`이 되고, `is_private`은 유지되고, 남은 멤버가 계속 대화 가능
- **`@Transactional` 없이 커밋시키는 테스트** — 기존 `AccountDeletionTest`가 그렇게 한다. `@Transactional` 테스트는 롤백돼 flush가 안 되므로 제약 조건을 검증하지 못한다. 위 탈퇴 검증이 여기 해당한다
- **소프트 삭제** — 삭제 후 구독·발행 거부, 목록에서 제외, `leave`는 여전히 가능(고아 멤버십 방지)
- **통지** — 강퇴가 `ROOM_KICKED`를, 삭제가 `ROOM_DELETED`를 실제로 전달하는지. `ROOM_MEMBERSHIP_REVOKED`가 나가면 프론트가 토스트를 억제하므로 **통지가 도달하지 않은 것과 구분이 안 된다** — 코드값을 직접 단언한다
- **Flyway fresh 부팅** — V1~V7 순차 적용
- **멀티 인스턴스 E2E는 하지 않는다** — 구독 회수가 단일 인스턴스 전제다
- **프론트** — `npm run lint && npm run build`. 실 브라우저로 잠긴 방 입장·강퇴·삭제 통지 확인

## 11. 범위 밖

- **방장 위임** — 주인 없는 방(시드 방, 탈퇴로 주인을 잃은 방)은 아무도 운영할 수 없는 동결 상태로 남는다
- **복원 UI와 purge 배치** — `deleted_at`만 두고 되살리기는 DB 직접 조작이다
- **완전 숨김 비공개방** — 방 이름은 전원에게 노출된다
- **방 이미지의 접근 제어** — 이미지는 서명 없는 S3 공개 URL이고 방과 아무 연결이 없다. **방을 삭제하거나 사람을 강퇴해도 URL을 가진 사람은 계속 읽을 수 있다.** 게다가 `ImageReferences.isReferenced`가 소프트 삭제된 방의 메시지도 참조로 세므로 orphan 태깅이 안 되어 수명주기 규칙도 지우지 못한다. 정석은 presigned URL이거나 앱 경유 프록시다
- **코드 충돌 경합 시의 로그 유출** — 코드는 저장 전에 중복을 미리 확인하지만, 확인과 저장 사이에 다른 요청이 같은 코드를 넣는 경합이 나면 DB의 `UNIQUE`가 거부하고 그때 Hibernate가 원본 SQL 예외를 로깅한다. MySQL의 중복 키 메시지에는 위반한 값이 들어 있으므로 그 순간 코드 한 개가 로그에 남는다. 12자·32자 알파벳(약 60비트) 공간에서 발생 확률이 무시할 수준이라 받아들인다. Hibernate 로거를 억제하는 방식은 쓰지 않는다 — 진짜 제약 위반까지 가려진다
- **인가 거부 사유의 세분화** — SEND 거부는 항상 `NOT_JOINED_ROOM`이다
- **방 이름 변경**
- **멀티 인스턴스 구독 회수** — 외부 브로커 릴레이가 선행 조건이다
