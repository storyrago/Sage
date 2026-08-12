# 답장 알림 설계

## 목표

내 메시지에 답장이 달린 방을, 랜딩 화면에서 일반 안읽음과 구분해 보여준다.

## 범위

**포함**

- 방별 "나에게 온 답장" 개수를 서버가 집계해 기존 안읽음 응답에 함께 싣는다.
- 답장이 실시간으로 도착하면 개인 큐 이벤트에 표시해 배지를 즉시 갱신한다.
- 랜딩 우표 배지가 답장 포함 여부에 따라 다르게 보인다.

**제외**

- 멘션(`@`) — 대상 지정·자동완성·본문 토큰·신뢰 경계 검증이 통째로 필요해 별도 작업으로 남긴다.
- 영속 알림함(`notifications` 테이블) — 알림 종류가 하나뿐이라 값어치가 없다. 종류가 늘면 그때 만든다.
- 브라우저 Web Push — Service Worker·VAPID·구독 관리가 추가된다.
- `@everyone` 류 방 전체 호출.

## 핵심 결정: 새 상태를 만들지 않는다

"나에게 온 답장"은 **파생 값**이다. 필요한 재료가 이미 전부 있다.

- `messages.reply_to_id` — 어느 메시지에 대한 답장인지
- `messages.member_id` — 부모 메시지의 작성자가 나인지
- `chatroom_members.last_read_message_id` — 내가 어디까지 읽었는지

그래서 조회할 때마다 다시 계산한다. 새 테이블도, 새 읽음 상태도 만들지 않는다.

이 선택의 이득이 두 가지다.

1. **F5를 견딘다.** 프론트 메모리에만 있는 알림 상태는 새로고침 한 번에 사라진다.
   PR #74 감사에서 걷어낸 "거짓말하는 UI"와 같은 부류가 된다.
2. **읽음 상태가 두 벌로 갈라지지 않는다.** 방에 들어가 `last_read_message_id`가 올라가면
   안읽음과 답장 카운트가 같은 순간에 0이 된다. 별도 읽음 처리 API가 필요 없다.

## 1. 서버 집계

`ChatRoomMemberRepository.findUnreadCountsByMemberId`는 이미 방별 그룹 집계다.
답장은 그 집계의 **부분집합**이므로 같은 쿼리에 필드를 얹는다.

```sql
LEFT JOIN m.replyTo p
...
COALESCE(SUM(CASE WHEN p.member = cm.member THEN 1 ELSE 0 END), 0) AS replyCount
```

### 함정 1 — 암묵 조인을 쓰면 안 된다

`m.replyTo.member`로 경로 표현식을 쓰면 JPQL 암묵 조인이 **INNER JOIN**으로 떨어진다.
`m`은 `LEFT JOIN ... ON`으로 붙어 있으므로, 답장이 하나도 없는 방은 `m` 행이 통째로 사라지고
**해당 방의 `unreadCount`가 0으로 뭉개진다.** 반드시 명시 `LEFT JOIN m.replyTo p`를 쓴다.

이 함정은 해피패스 테스트로 안 잡힌다. 답장이 섞인 방만 검증하면 통과한다.
**답장이 없는 방의 `unreadCount`가 보존되는지**를 별도 테스트로 잠근다.

### 함정 2 — `SUM`은 `null`을 반환한다

집계 대상 행이 없으면 `SUM`은 0이 아니라 `null`이다.
`UnreadCountProjection.getReplyCount()`가 원시형 `long`이라 `COALESCE` 없이는 NPE가 난다.

### 불변식

`unreadCount`의 조건(`m.member <> cm.member`, `m.deleted = false`,
`m.id > cm.lastReadMessageId`)이 이미 `ON` 절에 있다. `replyCount`는 그 위에 조건을 더할 뿐이므로

> **`replyCount ≤ unreadCount`**

가 구조적으로 성립한다. 테스트로 잠근다.

### 변경 지점

- `ChatRoomMemberRepository.findUnreadCountsByMemberId` — 조인 1줄, 집계 1줄
- `UnreadCountProjection` — `getReplyCount()`
- `UnreadCountResponse` — `replyCount`

새 엔드포인트를 만들지 않는다. `GET /api/chatrooms/unread`가 그대로 실어 보낸다.

## 2. 실시간 이벤트

`RedisSubscriber`는 새 메시지마다 방 멤버를 순회하며 `/user/queue/unread`로 개인 통지를 보낸다.
그 자리에서 "이 답장의 부모 메시지 작성자 == 이 멤버"를 판정해 `UnreadEvent.isReplyToMe`를 싣는다.

### 함정 3 — 트랜잭션 밖의 LAZY 초기화

판정에는 부모 메시지의 작성자 id가 필요하다. 그런데 `MessageResponse`에는 `replyToId`밖에 없고,
`replyTo.getMember().getId()`는 프록시 초기화를 요구한다.

**`MessageResponse.from(message)`는 `MessageService`의 트랜잭션 밖(`ChatMessageController`)에서
호출된다.** 필드를 그대로 추가하면 `LazyInitializationException`으로 메시지 전송 전체가 죽는다.
현재 `replyToId`가 무사한 것은 프록시 초기화 없이 식별자만 읽기 때문이다.

**해법: `MessageResponse` 생성을 트랜잭션 안(서비스)으로 옮긴다.**
REST 수정·삭제 경로에도 같은 지뢰가 있으므로 한 번에 정리한다.

### 변경 지점

- `MessageResponse` — `replyToMemberId` 추가, 생성 위치를 서비스로 이동
- `UnreadEvent` — `isReplyToMe` 추가
- `RedisSubscriber` — 멤버 순회 시 판정

## 3. 프론트

- `ChannelLanding` 우표 배지가 `replyCount > 0`이면 구분되게 표시한다.
  구체적인 모양은 실제 화면을 띄워 보고 정한다.
  PR #76에서 목업만 믿고 진행했다가 실제 화면에서 3건을 고쳐야 했다.
- `/user/queue/unread` 수신 시 `isReplyToMe`면 `replyCount`도 함께 증가시킨다(낙관적 갱신).
- 방 입장 시 `POST /api/chatrooms/{id}/read` → 서버 재조회로 둘 다 0이 된다.

## 4. 경계 조건

| 상황 | 동작 | 근거 |
|---|---|---|
| 내 메시지에 내가 답장 | 안 셈 | 기존 `m.member <> cm.member` 조건이 처리 |
| 부모 메시지가 삭제됨 | **셈** | 소프트 삭제라 행이 남고, 나에게 온 답장인 사실은 변하지 않음 |
| 답장이 삭제됨 | 안 셈 | 기존 `m.deleted = false` 조건 |
| 방을 나갔다 재입장 | 다시 셈 | `lastReadMessageId`가 null로 초기화 — 기존 안읽음과 동일 |
| 답장이 없는 방 | `replyCount = 0`, `unreadCount`는 보존 | 함정 1·2의 회귀 대상 |

## 5. 검증

- 리포지토리 쿼리 테스트 — `replyCount` 정확도, 위 경계 조건 5개,
  `replyCount ≤ unreadCount` 불변식
- `MessageResponse` 이동 후 STOMP 전송 회귀 — `LazyInitializationException`이 나지 않는지
- `./gradlew test`
- 프론트 `npm run lint && npm run build`
- 실제 화면 — 답장을 받은 방의 우표 배지가 구분되는지, 방에 들어가면 사라지는지

## 6. 배포 영향

스키마 변경 없음. Flyway 마이그레이션 없음. 환경변수 추가 없음.

## 7. 알려진 한계

- 앱이 꺼져 있는 동안 도착한 답장은 실시간 통지를 받지 못한다.
  다시 접속하면 서버 집계로 배지가 복원되므로 사실은 유실되지 않는다.
- 알림의 종류가 답장 하나뿐이라 "알림 목록" 화면이 없다. 방 단위 배지가 전부다.
- 답장 배지를 방 단위로만 센다. 어느 메시지인지까지 데려가지 않는다.
  기존 "여기부터 안 읽음" 구분선이 그 역할을 대신한다.
