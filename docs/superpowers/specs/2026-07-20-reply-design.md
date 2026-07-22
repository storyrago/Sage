# 답장(Reply) 기능 설계

**날짜:** 2026-07-20
**범위:** 특정 메시지에 답장 — 메시지에 부모 메시지 참조를 달고, 채팅 버블에 부모 인용을 표시.
**주의:** #44에서 **백엔드 없어 걷어냈던 프론트 답장 UI를 제대로 복원** + 백엔드 신규 구현. **스키마 변경 있음**(다른 실시간 기능들과 달리 마이그레이션 필요).

## 목표

- 메시지 입력 시 특정 메시지를 지정해 "답장"으로 보낼 수 있다.
- 답장 메시지 버블 안에 부모 메시지(작성자·내용)가 인용 표시된다.
- 실시간(STOMP)·REST 두 경로 모두 지원.

## 비목표

- 이모지 반응·메시지 삭제(#44에서 함께 제거됨)는 **복원하지 않음**. 답장만.
- 부모 메시지 스냅샷 저장(내용 복제) — 안 함. `replyToId`만 저장하고 프론트가 로드된 메시지에서 찾음.

## 핵심 결정

| # | 결정 | 이유 |
|---|------|------|
| 1 | `MessageResponse`에 **`replyToId`만** 담음(부모 스냅샷 X) | 메시지는 방 전체를 한 번에 로드(페이지네이션 없음) → 프론트가 `find`로 부모를 항상 찾음. #44에서 지운 UI 그대로 복원. |
| 2 | 스키마 반영 = **수동 ALTER**(ⓐ) | `ddl-auto: validate`라 `reply_to_id` 컬럼을 RDS에 **먼저** 반영해야 배포 시 기동됨. 명시적·통제 가능. 로컬은 `update`라 자동 생성. |
| 3 | `Message` **자기참조** `@ManyToOne`(nullable) | 부모 메시지를 FK로 참조. 답장이 아니면 null. |

## 백엔드

기존 메시지 생성 경로 **2개**(둘 다 `MessageRequest` + `MessageService.create` 사용):
- STOMP: `ChatMessageController.sendMessage` (`/pub/chatrooms/{id}/messages`)
- REST: `MessageController.sendMessage` (`POST /api/chatrooms/{id}/messages`)

### `Message` 엔티티 (변경)
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "reply_to_id")
private Message replyTo;   // null이면 일반 메시지
```
**기존 4-arg 생성자는 유지**하고 5-arg 오버로드 추가(4-arg는 replyTo=null로 위임):
```java
public Message(String content, String imageUrl, Member member, ChatRoom chatRoom) {
    this(content, imageUrl, member, chatRoom, null);
}
public Message(String content, String imageUrl, Member member, ChatRoom chatRoom, Message replyTo) {
    this.content = content;
    this.imageUrl = imageUrl;
    this.replyTo = replyTo;
    connect(member, chatRoom);
}
```
(기존 4-arg 사용처·테스트 무변경으로 보존.)

### `MessageRequest` (변경)
- `Long replyToId` 추가 (nullable). `@NotBlank content`는 유지.

### `MessageService.create` (시그니처 변경)
- `create(String content, String imageUrl, Long memberId, Long chatroomId, Long replyToId)`.
- 부모 조회: `Message replyTo = replyToId != null ? messageRepository.findById(replyToId).orElse(null) : null;` → **없으면 null 취급**(관대하게; 부모 삭제 기능 없음). `new Message(content, imageUrl, member, chatRoom, replyTo)`로 생성.
- **호출부 2곳**(STOMP·REST 컨트롤러) 모두 `messageRequest.getReplyToId()` 전달.

### `MessageResponse` (변경)
- `Long replyToId` 필드 추가.
- `from()`: `message.getReplyTo() != null ? message.getReplyTo().getId() : null`.

### 컨트롤러 2곳 (변경)
- STOMP·REST 핸들러가 `messageRequest.getReplyToId()`를 `create`에 전달.

## 스키마 마이그레이션 (배포 관문 ⚠️)

`messages` 테이블에 `reply_to_id` 컬럼 추가.

**로컬:** `application-local`이 `ddl-auto: update` → 앱 뜨면 컬럼·FK 자동 생성. 별도 작업 없음.

**운영(RDS):** `ddl-auto: validate` → **배포 전 수동 ALTER 필수.** 순서를 틀리면(코드 먼저 배포) validate 실패로 앱이 안 뜸.
```sql
ALTER TABLE messages ADD COLUMN reply_to_id BIGINT NULL;
-- FK는 validate가 강제하진 않지만 update가 만드는 것과 맞추려면:
ALTER TABLE messages ADD CONSTRAINT fk_messages_reply_to
    FOREIGN KEY (reply_to_id) REFERENCES messages(id);
```
**배포 순서:** ⑴ RDS에 위 ALTER 실행 → ⑵ develop 머지(CD 자동배포) → ⑶ validate 통과 기동 확인.

**사전 검증(로컬 스모크):** throwaway DB에 `update`로 스키마 생성 → `validate`로 재기동해 통과 확인(예전 `smoke_validate.sh` 흐름). 이걸로 "validate가 이 컬럼을 문제삼지 않음"을 배포 전에 보장.

## 프론트엔드 (#44에서 지운 것 복원)

#44(`5630f04`) 이전 상태에서 복원. `git show 5630f04 -- <file>`로 정확한 원본 확인 가능.

### `types.ts`
- `Message`에 `replyToId?: string` 복원.

### `lib/api.ts`
- `toMessage`: `replyToId: message.replyToId != null ? String(message.replyToId) : undefined`.
- `sendMessage(token, chatroomId, content, replyToId?)` — REST body에 `replyToId` 포함.

### `lib/stomp.ts`
- `send(chatroomId, content, replyToId?)` — SEND body를 `{ content, replyToId }`로.

### `App.tsx`
- `handleSendMessage(text, replyToId?)` — STOMP `send`/REST `sendMessage`에 `replyToId` 전달.

### `components/ChatArea.tsx` (복원)
- `replyMessage` state + 답장 배너(입력창 위) + 취소 버튼.
- 호버 액션에 **답장 버튼**(`CornerUpLeft`) → `setReplyMessage(msg)`.
- 버블 안 **부모 표시 박스**: `parentMsg = messages.find(m => m.id === msg.replyToId)` → 작성자·내용 인용.
- `handleSend`에서 `onSendMessage(cleanText, replyMessage?.id)` 후 `setReplyMessage(null)`.
- 미사용 아이콘(`CornerUpLeft`) import 복원.

## 검증

1. **백엔드 유닛/통합**: `MessageService.create`에 `replyToId` 주면 `Message.replyTo`가 연결되는지, `MessageResponse.from`이 `replyToId`를 반환하는지.
2. **스키마 스모크**: 로컬 throwaway DB update→validate 통과.
3. **백엔드 빌드**: `./gradlew build`.
4. **E2E**: 메시지 A 전송 → A.id로 답장 메시지 전송 → 방송된 `MessageResponse.replyToId == A.id` 확인.
5. **프론트**: `tsc` + `vite build` + 브라우저 육안(답장 배너·부모 인용 표시).

## 배포 영향

- **⚠️ 스키마 변경 있음** — presence·타이핑과 다르게 **RDS 마이그레이션 필요**. 위 순서(ALTER 먼저 → 배포) 준수.
- 배포 전 로컬 스모크로 validate 통과 보장.

## 향후

- 페이지네이션 도입 시 `MessageResponse`에 부모 스냅샷(닉네임·내용) 추가 고려(현재는 전체 로드라 불필요).
