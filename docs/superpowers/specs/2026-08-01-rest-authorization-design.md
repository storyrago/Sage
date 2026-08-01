# REST 인가 설계 (PR 2)

- 상태: 확정
- 선행: `2026-07-31-room-authorization-design.md` §2(A7·A9)·§7, PR 0·PR 1·구독 회수 머지 완료
- 범위: 백엔드 + 프론트 응답 타입 분리. 스키마 변경 없음

## 1. 배경과 목표

WS는 방 멤버만 구독·전송할 수 있게 막혀 있다. REST는 같은 자원을 같은 규칙으로 막지 않는다 — 인증만 하면 남의 방 참여자 명단을 읽고, 방을 나간 뒤에도 그 방의 메시지를 고칠 수 있으며, 전 회원의 이메일을 열거할 수 있다.

**목표:** REST의 멤버십 판정을 WS와 같은 함수(`RoomAccess`)에 태우고, 응답에서 필요 없는 개인정보를 뺀다.

## 2. 확인된 구멍

조사는 develop(구독 회수 머지 후) 기준이다.

| id | 엔드포인트 | 지금 | 무엇이 뚫려 있나 |
|---|---|---|---|
| R1 | `GET /api/members` | 인증만 | 전 회원의 email·profileImageUrl을 요청 1회로 전량 반환. 프론트는 호출하지 않는다 |
| R2 | `GET /api/members/{id}` | 인증만 | id가 순차값(`IDENTITY`)이라 1..N 열거로 R1과 같은 결과. 프론트가 쓴다(`ProfileModal`) |
| R3 | `GET /api/chatrooms/{chatroomId}/members` | 없음 | 컨트롤러가 `@AuthenticationPrincipal`을 받지 않는다. 비멤버가 임의 방의 참여자 명단을 수집한다. WS는 같은 정보(`/sub/chatrooms/{id}/presence`)를 멤버십으로 막는다 |
| R4 | `PATCH·DELETE /api/chatrooms/{chatroomId}/messages/{messageId}` | 작성자만 | 방 멤버십을 보지 않는다. 방을 나간 뒤에도 그 방의 자기 메시지를 고치고 지울 수 있고, 결과가 그 방으로 실시간 전파된다 |
| R5 | `GET /api/chatrooms/{id}` | 인증만 | 방 상세. 프론트가 호출하지 않는다 |
| R6 | `MessageService.getAllChatRoomMessages` | 없음 | 멤버십 검사 없이 방 전체 대화를 반환한다. 프로덕션 호출부가 없고 테스트 하나만 쓴다 |

**R4의 판정 축이 중요하다.** 수정·삭제 결과는 `MessageResponse.from(message)`가 채운 **엔티티의 방**으로 전파된다(`RedisSubscriber`가 그 값으로 배달한다). URL의 `chatroomId`는 응답에도 전파에도 흘러들지 않는다. 따라서 URL의 방을 기준으로 멤버십을 검사하면 **가짜 게이트**가 된다 — 공격자는 자기가 속한 방 id를 URL에 넣어 통과하고, 실제 쓰기와 전파는 원래 방으로 간다.

## 3. 결정

**D1. 안 쓰는 엔드포인트는 인가를 붙이지 않고 지운다.**

R1·R5·R6을 삭제한다. 인가를 붙이면 규칙·테스트·유지보수가 따라붙지만, 지우면 공격면 자체가 사라진다. `GET /api/members`에 권한을 걸려면 역할(admin) 개념을 새로 만들어야 하는데 이 PR의 범위가 아니다. 필요해지면 그때 인가와 함께 만든다.

**D2. 멤버십 판정은 `RoomAccess` 하나만 쓴다.**

R3·R4에 `RoomAccess.isMember(memberId, roomId)`를 통과시킨다. 검사는 **서비스 계층**에 둔다 — 컨트롤러에 두면 같은 서비스를 부르는 다른 경로(WS `@MessageMapping`)가 열린 채로 남는다.

같은 이유로 `MessageService`가 각자 하던 `existsByMemberAndChatRoom` 중복 검사도 `RoomAccess`로 교체한다(동작 불변). 선행 설계 §5-3이 약속한 "방 멤버인지 판단하는 코드가 레포에 하나만 존재한다"가 REST에서도 성립한다.

**D3. R4의 인가는 엔티티의 방 기준이고, URL 방이 다르면 거부한다.**

작성자 검사(기존)에 더해 `RoomAccess.isMember(memberId, message.getChatRoom().getId())`를 요구한다. URL의 `chatroomId`가 엔티티의 방과 다르면 그 자체로 거부한다.

거부 코드는 `MESSAGE_NOT_FOUND`(404)다. "그 방에 그 메시지는 없다"가 사실이고, 403으로 답하면 다른 방에 그 id가 존재한다는 사실을 알려주게 된다.

대가: 방을 나가면 그 방에 남긴 자기 메시지를 더 이상 지울 수 없다. 실서비스도 채널을 떠나면 그 채널의 메시지를 지우지 못한다.

**D4. 타인 조회용 DTO를 분리한다.**

`PublicMemberResponse(id, nickname, profileImageUrl, createdAt)`를 신설해 R2가 반환한다. `/me`·`PATCH /me`·온보딩·프로필사진 응답은 기존 `MemberResponse`(email 포함)를 그대로 쓴다 — 자기 이메일을 자기 계정 화면에서 보는 것은 정상이고, 지금 안 쓴다고 지웠다 되살리는 비용이 더 크다.

프론트는 `getMemberById`의 반환 타입만 분리한다. `ProfileModal`이 실제로 그리는 필드가 `id·nickname·profileImageUrl·createdAt`뿐이라 화면 변경은 없다.

## 4. 구조

| 파일 | 변경 |
|---|---|
| `controller/MemberController.java` | `GET /api/members` 삭제, `GET /{id}`가 `PublicMemberResponse` 반환 |
| `controller/ChatRoomController.java` | `GET /{id}` 삭제 |
| `controller/ChatRoomMemberController.java` | 참여자 목록에 `@AuthenticationPrincipal` 추가해 서비스로 전달 |
| `service/MemberService.java` | `getMemberList()` 삭제 |
| `service/ChatRoomService.java` | 단건 조회는 다른 서비스가 쓰므로 유지. 컨트롤러 진입점만 사라진다 |
| `service/ChatRoomMemberService.java` | `getChatRoomMembersById(chatroomId, requesterId)`로 시그니처 변경 + 멤버십 검사 |
| `service/MessageService.java` | `getAllChatRoomMessages` 삭제, `update`·`delete`에 방 인자와 멤버십 검사 추가, `create`·`getMessages`의 중복 검사를 `RoomAccess`로 교체 |
| `dto/PublicMemberResponse.java` (신규) | 타인 조회용 |
| `frontend/src/lib/api.ts` | `getMemberById` 반환 타입 분리 |

`RoomAccess`는 그대로 쓴다. 새 컴포넌트는 DTO 하나뿐이다.

`getChatRoomMembersById`는 `ChatRoomMemberN1Test`도 쓰므로 시그니처 변경에 맞춰 함께 갱신한다. `getMemberList()`의 호출부는 삭제 대상 컨트롤러뿐이다.

## 5. 오류 처리

- **비멤버** → `NOT_JOINED_ROOM`(403). 기존 코드다.
- **URL 방 ≠ 엔티티 방** → `MESSAGE_NOT_FOUND`(404).
- **작성자 아님** → `NOT_MESSAGE_OWNER`(403). 기존 동작을 유지한다.
- **검사 순서**: 메시지를 로드하고 → URL 방 일치 → 작성자 → 멤버십. 존재하지 않는 메시지가 멤버십 오류로 새지 않게 한다.
- 새 `ErrorCode`를 만들지 않는다.

## 6. 남기는 한계

- **id 열거는 그대로다.** email만 막는다. `GET /api/members/{id}`는 여전히 1..N으로 닉네임·프로필사진·가입일을 수집할 수 있다. 관계 기반 제한(같은 방 멤버만 조회)은 프로필 모달을 방 밖에서도 열 수 있어야 하는지에 달려 있어 범위 밖이다.
- **방 목록은 전체 공개다.** `GET /api/chatrooms`가 모든 방을 반환하고 입장에 승인이 없다. 비공개방은 보류된 설계다.
- **S3 orphan 태깅(A10)은 PR 3이다.** 프로필 이미지와 메시지 이미지 **양쪽 진입점**에서 `tagAsOrphan`이 URL 문자열을 참조 해제의 근거로 신뢰한다. 한쪽만 막으면 다른 쪽이 같은 기능을 제공한다.
- **레이트리밋은 로그인에만 있다.** 남은 열거 경로에 요청 제한이 없다.

## 7. 검증

**자동 테스트 — "된다"가 아니라 "안 된다"를 본다.**

- 비멤버가 방 참여자 목록을 조회하면 거부된다. 멤버는 조회된다
- 방을 나간 회원이 그 방에 남긴 자기 메시지를 수정·삭제하지 못한다
- **자기가 속한 방 id를 URL에 넣어도** 다른 방의 메시지에 손대지 못한다(가짜 게이트 방지)
- 존재하지 않는 방 id를 URL에 넣어도 마찬가지로 거부된다
- 작성자가 아닌 멤버는 여전히 `NOT_MESSAGE_OWNER`로 거부된다
- `GET /api/members/{id}` 응답에 email이 없다. `/me` 응답에는 있다
- 삭제한 엔드포인트가 더 이상 매핑되지 않는다
- `create`·`getMessages`의 기존 동작이 `RoomAccess` 교체 뒤에도 그대로다(회귀)

**프론트 검증**: `npm run lint && npm test && npm run build`. `ProfileModal`이 그리는 필드가 그대로인지 타입으로 확인한다.

**배포 후 실측**

- 방에 입장한 상태에서 참여자 목록과 프로필 모달이 정상 동작하는지
- 방을 나간 뒤(API 직접 호출) 그 방의 자기 메시지 수정이 거부되는지
- 로그인·온보딩·프로필 수정 화면이 그대로 동작하는지(`/me` 응답을 쓰는 경로)
