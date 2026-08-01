# 회원 탈퇴 시 메시지 익명화 설계

- 상태: 확정
- 선행: `2026-08-01-s3-tagging-contract-design.md`(이미지 정리), `2026-08-01-subscription-revocation-design.md`(구독 회수)
- 범위: 백엔드 + 프론트 표시 한 곳. **마이그레이션 없음**

## 1. 배경과 목표

`MemberService.delete`는 탈퇴자의 메시지를 하드 삭제한다. 이 정책이 두 가지를 만든다.

**계정을 지울 수 없는 사용자가 있다.** `V1__baseline.sql:36`의 `fk_messages_reply`에 `ON DELETE` 절이 없어 MySQL 기본값 RESTRICT다. `MessageService.create`는 같은 방이면 **작성자와 무관하게** 답장을 연결하므로, 누군가 내 메시지에 답장한 상태에서 내가 탈퇴하면 내 메시지 삭제가 그 답장 행에 걸려 실패한다. 요청은 500으로 끝나고 트랜잭션이 롤백된다 — 계정이 지워지지 않는다.

부수 피해가 더 크다. 탈퇴가 롤백되면 `AFTER_COMMIT`으로 걸린 WS 구독 회수와 S3 이미지 정리가 **하나도 실행되지 않는다.**

**남의 대화가 훼손된다.** 탈퇴자의 메시지가 사라지면 그 메시지를 인용한 답장은 원본을 잃는다. 지워지는 것은 탈퇴자의 데이터지만, 잃는 것은 남은 사람들의 문맥이다.

**이 결함은 테스트가 잡을 수 없었다.** 탈퇴를 호출하는 테스트가 모두 `@Transactional`이라 롤백되고, `delete()` 이후 질의가 없어 DELETE SQL이 flush되지 않는다. 실제 SQL이 CI에서 한 번도 실행된 적이 없다.

**목표:** 탈퇴가 항상 성공하게 하고, 대화를 보존하면서 개인정보를 지운다.

## 2. 결정

**D1. 메시지를 지우지 않고 작성자 참조만 끊는다.**

`UPDATE messages SET member_id = NULL WHERE member_id = ?`를 실행한 뒤 회원 행을 지운다. 개인정보(이메일·비밀번호·닉네임·프로필 사진·소셜 식별자)는 회원 행과 함께 사라지고, 메시지 본문과 대화 구조는 남는다.

실서비스가 하는 방식이다. 계정 삭제로 남의 대화 기록을 훼손하지 않는다.

**D2. 마이그레이션을 추가하지 않는다.**

`messages.member_id`는 이미 nullable이다(`V1__baseline.sql:27`). 하드 삭제 경로가 사라지므로 `fk_messages_reply`도 손댈 이유가 없다 — 답장이 가리키는 원본이 계속 존재한다.

**D3. 조회가 작성자 없는 메시지를 떨어뜨리지 않게 한다.**

`MessageRepository.findLatestByChatRoom`·`findOlderByChatRoom`이 `JOIN FETCH m.member`(inner join)를 쓴다. 그대로 두면 익명화된 메시지가 **목록에서 조용히 사라진다.** `LEFT JOIN FETCH`로 바꾼다.

**D4. 서버는 사실만 내려보낸다.**

`MessageResponse`의 `memberId`·`nickname`·`profileImageUrl`을 `null`로 준다. "삭제된 사용자" 문구는 프론트가 표시한다. UI 문구를 닉네임 데이터에 채워 넣지 않는다.

프론트의 변환 지점은 `frontend/src/lib/api.ts`의 `toMessage`다. 지금은 `userId: String(message.memberId)`, `userName: message.nickname`, `avatarForId(message.memberId)`를 그대로 쓰므로 `null`이 들어오면 각각 `"null"` 문자열·빈 이름·잘못된 아바타가 된다. 셋 다 익명 작성자용 값으로 바꾼다.

**익명 작성자는 프로필을 열 수 없다.** `ChatArea`가 아바타와 이름에 `onOpenProfile(msg.userId)`를 걸어 두었는데, 존재하지 않는 회원을 조회하면 모달이 오류를 띄운다. 작성자가 없으면 그 버튼을 걸지 않는다.

**D5. 익명화된 메시지는 아무도 수정·삭제할 수 없다.**

`MessageService.update`·`delete`가 `message.getMember().getId().equals(memberId)`로 소유권을 본다. 작성자가 `null`이면 NPE로 500이 난다. 작성자가 없으면 `NOT_MESSAGE_OWNER`로 거부한다.

**D6. 탈퇴 시 정리 대상은 프로필 사진뿐이다.**

메시지가 남으므로 그 이미지들은 여전히 참조 상태다. 지금처럼 메시지 이미지 URL을 모아 `ImageDereferencedEvent`를 발행하면 선행 설계의 참조 게이트가 전부 건너뛰므로, 헛도는 조회만 URL 수만큼 늘어난다. 프로필 사진 한 건만 발행한다.

`MemberDeletedEvent`(구독 회수)는 그대로 두고 순서도 유지한다 — 보안 동작이 먼저다.

## 3. 구조

| 파일 | 변경 |
|---|---|
| `repository/MessageRepository.java` | `deleteByMember` 제거, `@Modifying`으로 작성자를 비우는 쿼리 추가. 조회 2개를 `LEFT JOIN FETCH`로 |
| `service/MemberService.java` | `delete()`에서 메시지 삭제 대신 익명화, 이미지 수집을 프로필만으로 |
| `service/MessageService.java` | `update`·`delete`의 소유권 검사에 널 가드 |
| `dto/MessageResponse.java` | 작성자 없음을 `null`로 표현 |
| `frontend/src/lib/api.ts` | `toMessage`에서 작성자 없음을 "삭제된 사용자"로 변환 |
| `frontend/src/components/ChatArea.tsx` | 익명 작성자는 프로필 열기를 걸지 않는다 |

새 컴포넌트를 만들지 않는다. 이벤트 타입도 그대로다.

## 4. 데이터 흐름

```
DELETE /api/members/me
  └ delete(): 프로필 URL 수집
      ├ chatRoomMemberRepository.deleteByMember   (멤버십 제거)
      ├ messages의 member_id를 NULL로            (익명화, 메시지는 남는다)
      ├ memberRepository.delete                  (개인정보 제거)
      └ 커밋
          ├ MemberDeletedEvent      → WS 구독 회수
          └ ImageDereferencedEvent  → 프로필 사진 1건, 참조 게이트를 지난다
```

답장은 원본이 남으므로 `fk_messages_reply`가 걸리지 않는다.

## 5. 오류 처리와 엣지

- **익명화된 메시지의 수정·삭제** → `NOT_MESSAGE_OWNER`(403). NPE로 새지 않는다.
- **소프트 삭제된 메시지** → 그대로다. 이미 `imageUrl`이 비어 있고 작성자만 사라진다.
- **안읽음·프레즌스** → 멤버십 행이 지워지므로 그 회원은 어느 방의 명단에도 남지 않는다.
- **프로필 사진을 남이 참조 중** → 선행 설계의 참조 게이트가 태깅을 건너뛴다.
- **탈퇴 트랜잭션 실패** → 이제 답장 때문에 실패할 경로가 없다. 다른 이유로 실패하면 롤백되고 이벤트도 나가지 않는다(기존과 같다).

## 6. 남기는 한계

- **메시지 본문은 남는다.** 본문에 개인정보를 적었다면 익명화만으로 지워지지 않는다. 탈퇴 전에 개별 삭제해야 하고, 그 경로는 이미 존재한다(작성자 본인의 메시지 삭제).
- **이미 하드 삭제된 과거 탈퇴자의 메시지는 돌아오지 않는다.** 이번 변경은 앞으로의 탈퇴에만 적용된다.
- **"삭제된 사용자" 문구는 프론트 하드코딩이다.** 이 앱에 i18n이 없다.
- **탈퇴자의 소켓과 토큰은 만료까지 살아 있다.** 계정 삭제 시 토큰 무효화는 별도 논점이다(선행 설계에 한계로 남아 있다).
- **`fk_messages_reply`의 `ON DELETE` 정책은 그대로 둔다.** 지금은 하드 삭제 경로가 없어 문제가 되지 않지만, 나중에 방 삭제나 관리자 삭제를 만들면 같은 함정에 다시 빠진다. 그때 정책을 함께 정한다.

## 7. 검증

**자동 테스트 — "지워지지 않는다"와 "빠지지 않는다"를 본다.**

- **답장을 받은 적 있는 회원도 탈퇴할 수 있다.** `@Transactional` 없이 실제로 커밋하는 통합 테스트로 고정한다. 이 결함이 CI를 통과한 이유가 롤백이었으므로, 커밋하지 않는 테스트는 이 항목을 검증할 수 없다
- 탈퇴 후 그 회원의 메시지가 남아 있고 작성자가 `null`이다
- 작성자 없는 메시지가 목록 조회에 포함된다(`LEFT JOIN FETCH` 회귀)
- 작성자 없는 메시지의 수정·삭제 시도가 `NOT_MESSAGE_OWNER`로 거부된다(NPE가 아니다)
- 탈퇴 시 프로필 이미지에 대해서만 정리 이벤트가 나간다
- 구독 회수 이벤트가 이미지 정리보다 먼저 발행된다(기존 테스트 유지)

**프론트 검증**: `npm run lint && npm test && npm run build`. 익명 작성자의 메시지에서 프로필 모달이 열리지 않는 것은 배포 후 실측으로 확인한다(이 경로를 덮는 프론트 테스트 인프라가 없다).

**배포 후 실측**

- 답장을 주고받은 계정으로 탈퇴가 성공하는지
- 탈퇴자의 메시지가 대화에 남고 "삭제된 사용자"로 보이는지
- 그 메시지를 인용한 답장이 원본을 계속 가리키는지
- 탈퇴자의 프로필 사진에 orphan 태그가 붙는지(다른 곳에서 안 쓰는 경우)
