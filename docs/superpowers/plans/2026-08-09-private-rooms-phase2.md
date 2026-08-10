# 비공개방 2단계(방장 운영) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 방 주인이 자기 방을 운영할 수 있게 한다 — 강퇴·차단 해제·코드 재발급·공개↔비공개 전환·방 삭제, 그리고 그 결과를 접속 중인 사람에게 실시간으로 알린다.

**Architecture:** 1단계가 컬럼·인가·읽기 필터를 전부 깔아 뒀고 **쓰기 측만 비어 있다.** `ChatRoom` 엔티티에 상태 변경 메서드가 하나도 없어서(생성자 private, 팩토리 2개, 게터뿐) 소프트 삭제·재발급·전환이 물리적으로 불가능하다. 그래서 엔티티 변경 메서드 + 더티체킹을 토대로 삼고, 그 위에 서비스 5개와 엔드포인트 5개를 얹는다. 통지는 기존 `RoomSubscriptionRevoker` 사슬에 **사유를 끝까지 흘려보내는 것**으로 처리한다.

**Tech Stack:** Spring Boot 3, Spring Data JPA, JUnit 5 + AssertJ + Mockito, React 19 + TypeScript + Vite

**설계 문서:** [`docs/superpowers/specs/2026-08-05-private-rooms-design.md`](../specs/2026-08-05-private-rooms-design.md) — §5.3(방장 전용), §7(통지·구독 회수)
**1단계 플랜:** [`docs/superpowers/plans/2026-08-05-private-rooms-phase1.md`](2026-08-05-private-rooms-phase1.md)

## Global Constraints

- **Flyway 마이그레이션을 추가하지 않는다.** V7이 이번 5개 기능에 필요한 컬럼(`created_by`·`is_private`·`invite_code`·`deleted_at`)과 `chatroom_bans` 테이블을 이미 만들어 뒀다. V8을 붙이면 불필요한 배포 리스크만 는다.
- **`ChatRoomService`와 `ChatRoomMemberService`는 클래스 레벨이 `@Transactional(readOnly = true)`다.** 이번에 추가하는 변경 메서드에 `@Transactional`을 빠뜨리면 더티체킹 UPDATE가 안 나간다. 엔티티 변경 방식에서는 이 애노테이션이 **유일한 저장 트리거**다.
- **`ChatRoomResponse.from(ChatRoom, Long requesterId, boolean joined)` 3인자 팩토리만 쓴다.** 단일 인자 팩토리를 되살리면 초대 코드가 전원에게 나간다(생성자는 private이다).
- **`ChatRoomMemberRepository.findMembersByChatRoomId`에 `deletedAt` 필터를 넣지 않는다.**
- **`ChatRoomMemberRepository.existsByMemberIdAndChatRoomId`를 쓰지 않는다.** 삭제 필터가 없어서 삭제된 방에서 멤버십이 살아 있는 것으로 판정된다. 이름이 직관적이라 무심코 집기 쉽다 — `RoomAccess.isMember` 또는 `existsActiveMembership`을 쓴다.
- **방장 판정은 `ChatRoom.isOwnedBy(Long memberId)`만 쓴다.** 이미 있고 `createdBy`·`createdBy.getId()` 둘 다 null 안전이라 시드 방에서 `false`다. `room.getCreatedBy().getId().equals(...)`를 새로 쓰면 NPE 500이 나고 스택트레이스가 CloudWatch로 나간다.
- **초대 코드는 요청·응답 본문에만.** URL 경로·쿼리스트링·리다이렉트 `Location`·로그에 넣지 않는다. nginx가 `$request`와 `$http_referer`를 CloudWatch로 보낸다.
- **통지 목적지 문자열은 `"/sub/chatrooms/" + roomId`로 고정이고, 전송은 `convertAndSendToUser(memberId, "/queue/errors", payload)` 개인 큐 직송이다.** 프론트 `App.tsx`의 `roomIdFromDestination`이 그 포맷을 파싱한다. `/typing`·`/presence`를 붙이면 방을 특정하지 못한다. 방 토픽(`/sub/chatrooms/{id}`)으로 보내면 그 구독은 회수 시점에 이미 지워져 본인에게 도달하지 못한다.
- **단일 인스턴스 전제.** `enableSimpleBroker` + 로컬 `SimpUserRegistry`라 다른 인스턴스에 붙은 세션은 회수도 통지도 안 된다. 현재 배포는 단일 인스턴스다.
- 커밋 메시지·주석에 배경 서사("누락", "핫픽스", "깨져 있었다")를 쓰지 않는다. 변경의 목적만 쓴다.
- 백엔드 검증은 `./gradlew test`, 프론트는 `cd frontend && npm run lint && npm run build` + `npx vitest run`.
- 브랜치는 `feat/room-management`(`d895351`에서 분기, PR #100의 React 타입 포함).

## 설계 문서와 다른 점

설계 §7은 `RoomDeletedEvent(roomId)`만 싣고 **리스너가 `findMembersByChatRoomId`로 회수 대상을 조회**하는 방식이다. 이 플랜은 **트랜잭션 안에서 멤버 id를 걷어 `RoomDeletedEvent(roomId, List<Long> memberIds)`로 실어 보낸다.** 이유 둘:

- `AFTER_COMMIT`에서 DB를 읽는 것 자체가 트랜잭션 경계 밖 조회다. 선례(`ImageCleanupListener`)는 조회 빈에 `@Transactional(readOnly = true)`를 걸어 그 경계를 만들어 준다 — 페이로드에 실으면 조회 자체가 사라진다.
- 설계 방식은 `findMembersByChatRoomId`에 삭제 필터가 **영원히 없어야** 성립한다. 누가 그 필터를 넣는 순간 통지가 조용히 죽는다(빈 리스트, 예외 없음). 페이로드 방식은 그 의존을 없앤다.

두 방식 다 요구사항을 만족하지만 후자가 덜 부서진다. 그래도 `findMembersByChatRoomId`에 필터를 넣지 말라는 제약은 유지한다 — 그 쿼리의 다른 소비자(안읽음 fan-out)가 있다.

## 이번 단계에 없는 것

방장 위임, 삭제 복원 UI, `deleted_at` purge 배치, 멀티 인스턴스 구독 회수, 방 이름 변경.

---

### Task 1: 엔티티 변경 메서드와 오류 코드

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/ChatRoom.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/ChatRoomMutationTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ChatRoom.softDelete()` — `deletedAt`을 현재 시각으로. 이미 삭제됐으면 아무것도 하지 않는다
  - `ChatRoom.isDeleted()` → `boolean`
  - `ChatRoom.reissueInviteCode(String newCode)` — 잠긴 방에서만 유효
  - `ChatRoom.makePublic()` — `isPrivate=false`, `inviteCode=null`
  - `ChatRoom.makePrivate(String code)` — `isPrivate=true`, `inviteCode=code`
  - `ErrorCode.NOT_ROOM_OWNER(403, "방장만 할 수 있어요.")`
  - `ErrorCode.ROOM_DELETED(403, "방이 삭제되었어요.")`
  - `ErrorCode.ROOM_KICKED(403, "방에서 내보내졌어요.")`

`ChatRoom`은 지금 게터와 `isPrivate()`·`isOwnedBy(Long)`뿐이고 생성자가 private이다. 테스트가 `ReflectionTestUtils.setField(room, "deletedAt", ...)`로 값을 넣는 것이 그 증거다. 이 태스크가 그 우회를 없앤다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/room/ChatRoomMutationTest.java`

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomMutationTest {

    @Test
    void 소프트_삭제는_시각을_남긴다() {
        ChatRoom room = ChatRoom.publicRoom("방", null);
        assertThat(room.isDeleted()).isFalse();

        room.softDelete();

        assertThat(room.isDeleted()).isTrue();
        assertThat(room.getDeletedAt()).isNotNull();
    }

    @Test
    void 이미_삭제된_방을_다시_삭제해도_시각이_바뀌지_않는다() {
        ChatRoom room = ChatRoom.publicRoom("방", null);
        room.softDelete();
        var first = room.getDeletedAt();

        room.softDelete();

        assertThat(room.getDeletedAt()).isEqualTo(first);
    }

    @Test
    void 공개로_바꾸면_코드가_사라진다() {
        ChatRoom room = ChatRoom.privateRoom("잠김", null, "ABCDEFGHJKLM");

        room.makePublic();

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
    }

    @Test
    void 비공개로_바꾸면_코드가_생긴다() {
        ChatRoom room = ChatRoom.publicRoom("열림", null);

        room.makePrivate("MNPQRSTUVWXY");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("MNPQRSTUVWXY");
    }

    @Test
    void 코드_재발급은_코드만_바꾼다() {
        ChatRoom room = ChatRoom.privateRoom("잠김", null, "ABCDEFGHJKLM");

        room.reissueInviteCode("MNPQRSTUVWXY");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("MNPQRSTUVWXY");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*ChatRoomMutationTest*'`
Expected: 컴파일 실패 — `softDelete`·`isDeleted`·`makePublic`·`makePrivate`·`reissueInviteCode`가 없음

- [ ] **Step 3: `ChatRoom`에 변경 메서드를 더한다**

`isOwnedBy` 아래에 붙인다.

```java
    /** 이미 삭제된 방을 다시 삭제해도 최초 삭제 시각을 유지한다. */
    public void softDelete() {
        if (deletedAt == null) {
            deletedAt = LocalDateTime.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 잠금과 코드는 항상 함께 움직인다. 코드만 지우면 잠긴 방이 공개방이 된다. */
    public void makePublic() {
        this.isPrivate = false;
        this.inviteCode = null;
    }

    public void makePrivate(String inviteCode) {
        this.isPrivate = true;
        this.inviteCode = inviteCode;
    }

    public void reissueInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }
```

- [ ] **Step 4: `ErrorCode`에 셋을 더한다**

`// ChatRoom` 구획에 넣는다.

```java
    NOT_ROOM_OWNER(403, "방장만 할 수 있어요."),
    ROOM_DELETED(403, "방이 삭제되었어요."),
    ROOM_KICKED(403, "방에서 내보내졌어요."),
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew test --tests '*ChatRoomMutationTest*'`
Expected: 5개 PASS

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/domain/ChatRoom.java \
        src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java \
        src/test/java/com/example/springboot_realtimechat/room/ChatRoomMutationTest.java
git commit -m "feat(room): 방 상태를 바꾸는 메서드와 방장 운영 오류 코드를 더한다"
```

---

### Task 2: 구독 회수에 사유를 끝까지 전달한다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/RoomSubscriptionRevoker.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/event/RoomLeftEvent.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/event/SubscriptionRevocationListener.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java` (이벤트 발행 한 줄)
- Modify: `src/test/java/com/example/springboot_realtimechat/ws/RoomSubscriptionRevokerTest.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationListenerTest.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationEventTest.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/ws/SubscriptionRevocationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1의 `ErrorCode.ROOM_KICKED`·`ROOM_DELETED`
- Produces:
  - `RoomSubscriptionRevoker.revokeRoom(Long memberId, Long roomId, ErrorCode reason)`
  - `RoomSubscriptionRevoker.revokeAll(Long memberId, ErrorCode reason)`
  - `RoomLeftEvent(Long memberId, Long roomId, ErrorCode reason)`

지금 `notifyRevoked`가 `ErrorCode.ROOM_MEMBERSHIP_REVOKED`를 하드코딩한다. **그대로 두면 강퇴당한 사람도 그 코드를 받고, 프론트(`App.tsx`)가 그 코드에서 토스트를 의도적으로 억제하므로 아무 설명 없이 튕긴다.** 통지 미도달과 구분도 안 된다.

사슬은 3단계로 끝난다: `revokeRoom`(공개) → `revoke`(private) → `notifyRevoked`(private). `unsubscribe`와 `WsErrorResponse`는 손대지 않는다 — 사유는 기존 `code`/`message` 필드에 실려 나가고 UNSUBSCRIBE 프레임에는 도달하지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`RoomSubscriptionRevokerTest`에 아래 테스트를 더한다(기존 테스트의 픽스처 이름은 파일을 열어 맞춘다).

```java
    @Test
    void 강퇴_사유가_통지_코드로_나간다() {
        // 기존 테스트가 세션·구독을 세팅하는 방식을 그대로 따른다
        revoker.revokeRoom(7L, 3L, ErrorCode.ROOM_KICKED);

        WsErrorResponse sent = capturedPayload();   // 기존 테스트의 캡처 헬퍼를 쓴다
        assertThat(sent.code()).isEqualTo("ROOM_KICKED");
        assertThat(sent.destination()).isEqualTo("/sub/chatrooms/3");
    }

    @Test
    void 삭제_사유가_통지_코드로_나간다() {
        revoker.revokeRoom(7L, 3L, ErrorCode.ROOM_DELETED);

        assertThat(capturedPayload().code()).isEqualTo("ROOM_DELETED");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomSubscriptionRevokerTest*'`
Expected: 컴파일 실패 — 3인자 `revokeRoom`이 없음

- [ ] **Step 3: 회수기 시그니처를 바꾼다**

```java
    /** 방 하나의 구독 3종을 회수한다. */
    public void revokeRoom(Long memberId, Long roomId, ErrorCode reason) {
        revoke(memberId, room -> room.equals(roomId), reason);
    }

    /** 그 회원의 모든 방 구독을 회수한다. 개인 큐 구독은 남긴다. */
    public void revokeAll(Long memberId, ErrorCode reason) {
        revoke(memberId, room -> true, reason);
    }

    private void revoke(Long memberId, Predicate<Long> roomFilter, ErrorCode reason) {
        // ... 기존 본문 그대로, notifyRevoked 호출만 사유를 넘긴다 ...
        for (Long roomId : revokedRooms) {
            try {
                notifyRevoked(memberId, roomId, reason);
            } catch (Exception e) {
                log.warn("구독 회수 통지 실패: roomId={}", roomId, e);
            }
        }
    }

    private void notifyRevoked(Long memberId, Long roomId, ErrorCode reason) {
        messagingTemplate.convertAndSendToUser(
                String.valueOf(memberId),
                "/queue/errors",
                new WsErrorResponse(
                        reason.name(),
                        reason.getMessage(),
                        "/sub/chatrooms/" + roomId));
    }
```

- [ ] **Step 4: 이벤트에 사유를 더한다**

```java
package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.global.exception.ErrorCode;

/** reason은 회수 통지에 그대로 실린다. 자진 퇴장과 강퇴가 여기서 갈린다. */
public record RoomLeftEvent(Long memberId, Long roomId, ErrorCode reason) {
}
```

- [ ] **Step 5: 리스너와 발행 지점을 맞춘다**

`SubscriptionRevocationListener`:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomLeft(RoomLeftEvent event) {
        try {
            revoker.revokeRoom(event.memberId(), event.roomId(), event.reason());
        } catch (Exception e) {
            log.warn("방 구독 회수 실패: memberId={}, roomId={}", event.memberId(), event.roomId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberDeleted(MemberDeletedEvent event) {
        try {
            revoker.revokeAll(event.memberId(), ErrorCode.ROOM_MEMBERSHIP_REVOKED);
        } catch (Exception e) {
            log.warn("회원 구독 회수 실패: memberId={}", event.memberId(), e);
        }
    }
```

`ChatRoomMemberService.leave`의 발행 한 줄:

```java
        eventPublisher.publishEvent(new RoomLeftEvent(memberId, chatRoomId, ErrorCode.ROOM_MEMBERSHIP_REVOKED));
```

- [ ] **Step 6: 깨진 기존 테스트를 고친다**

시그니처 변경으로 컴파일이 깨지는 파일이 넷이다. 각각 열어 2인자 호출을 3인자로 바꾼다 — 자진 퇴장 맥락이면 `ErrorCode.ROOM_MEMBERSHIP_REVOKED`를 넘긴다.

```bash
grep -rn "revokeRoom(\|revokeAll(\|new RoomLeftEvent(" src/test src/main
```

`RoomSubscriptionRevokerTest`의 `assertThat(sent.code()).isEqualTo("ROOM_MEMBERSHIP_REVOKED")` 단언은 그 테스트가 자진 퇴장 사유를 넘기도록 바꾼 뒤 그대로 둔다.

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 구독 회수 통지에 사유를 전달한다"
```

---

### Task 3: 방 삭제(소프트)

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/event/RoomDeletedEvent.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/event/SubscriptionRevocationListener.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomDeletionTest.java`

**Interfaces:**
- Consumes: Task 1의 `ChatRoom.softDelete()`·`ErrorCode.NOT_ROOM_OWNER`·`ROOM_DELETED`, Task 2의 3인자 `revokeRoom`
- Produces:
  - `ChatRoomService.delete(Long chatRoomId, Long requesterId)` → `void`
  - `RoomDeletedEvent(Long roomId, List<Long> memberIds)`
  - `DELETE /api/chatrooms/{id}` → 204

**회수 대상을 이벤트 페이로드에 싣는다.** `AFTER_COMMIT`에서 DB를 다시 읽지 않기 위해서다. 트랜잭션 안에서 멤버 id를 걷어 실어 보내면 리스너가 조회할 필요가 없고, `findMembersByChatRoomId`에 필터가 들어가도 통지가 죽지 않는다.

**삭제해도 멤버십 행은 지우지 않는다.** 소프트 삭제라 되살릴 여지를 남기고, 목록·인가는 이미 `deleted_at`으로 걸러진다. 따라서 방장의 `OWNER_CANNOT_LEAVE`는 그대로지만 그 방이 목록에서 사라지므로 사용자가 막히지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.RoomAccess;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomDeletionTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 방장은_방을_삭제할_수_있다() {
        Member owner = memberService.create("d1-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제방", false, owner.getId());

        chatRoomService.delete(room.getId(), owner.getId());

        assertThat(chatRoomRepository.findById(room.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(chatRoomService.getAllChatRooms())
                .extracting(ChatRoom::getId)
                .doesNotContain(room.getId());
    }

    @Test
    void 방장이_아니면_삭제할_수_없다() {
        Member owner = memberService.create("d2-owner@e.com", "1234", "주인");
        Member other = memberService.create("d2-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("삭제방2", false, owner.getId());
        chatRoomMemberService.join(other.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.delete(room.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 주인_없는_방은_아무도_삭제할_수_없다() {
        Member anyone = memberService.create("d3-any@e.com", "1234", "아무나");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(anyone.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.delete(room.getId(), anyone.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 삭제된_방에서는_멤버십이_인정되지_않고_멤버십_행은_남는다() {
        Member owner = memberService.create("d4-owner@e.com", "1234", "주인");
        Member guest = memberService.create("d4-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("삭제방4", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomService.delete(room.getId(), owner.getId());

        assertThat(roomAccess.isMember(guest.getId(), room.getId())).isFalse();
        assertThat(chatRoomMemberRepository.findChatRoomIdsByMemberId(guest.getId()))
                .doesNotContain(room.getId());
        // 소프트 삭제라 행 자체는 남는다
        assertThat(chatRoomMemberRepository.count()).isPositive();
    }

    @Test
    void 이미_삭제된_방을_다시_삭제해도_터지지_않는다() {
        Member owner = memberService.create("d5-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제방5", false, owner.getId());
        chatRoomService.delete(room.getId(), owner.getId());

        // 두 번째 호출은 삭제된 방을 찾지 못해 404다
        assertThatThrownBy(() -> chatRoomService.delete(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 삭제된_방에서도_나갈_수_있다() {
        Member owner = memberService.create("d6-owner@e.com", "1234", "주인");
        Member guest = memberService.create("d6-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("삭제방6", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomService.delete(room.getId(), owner.getId());

        assertThatCode(() -> chatRoomMemberService.leave(guest.getId(), room.getId()))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomDeletionTest*'`
Expected: 컴파일 실패 — `ChatRoomService.delete`가 없음

- [ ] **Step 3: 이벤트를 만든다**

```java
package com.example.springboot_realtimechat.event;

import java.util.List;

/**
 * 회수 대상을 페이로드에 싣는다. AFTER_COMMIT 시점에 다시 조회하지 않기 위해서다.
 */
public record RoomDeletedEvent(Long roomId, List<Long> memberIds) {
}
```

- [ ] **Step 4: 서비스에 삭제를 더한다**

`ChatRoomService`에 `ApplicationEventPublisher`를 주입한다(이 서비스는 아직 갖고 있지 않다).

```java
    @Transactional
    public void delete(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);

        // 커밋 후에는 조회하지 않는다. 회수 대상을 지금 걷어 이벤트에 싣는다.
        List<Long> memberIds = chatRoomMemberRepository.findMembersByChatRoomId(chatRoomId).stream()
                .map(Member::getId)
                .toList();

        chatRoom.softDelete();
        eventPublisher.publishEvent(new RoomDeletedEvent(chatRoomId, memberIds));
    }

    /** 주인이 없는 방(시드 방, 주인이 탈퇴한 방)은 아무도 운영할 수 없다. */
    private void requireOwner(ChatRoom chatRoom, Long requesterId) {
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
    }
```

`requireOwner`는 Task 4~7이 그대로 재사용한다.

- [ ] **Step 5: 리스너에 핸들러를 더한다**

한 명의 회수 실패가 나머지를 막지 않도록 **멤버별로 격리**한다.

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomDeleted(RoomDeletedEvent event) {
        for (Long memberId : event.memberIds()) {
            try {
                revoker.revokeRoom(memberId, event.roomId(), ErrorCode.ROOM_DELETED);
            } catch (Exception e) {
                log.warn("삭제된 방 구독 회수 실패: memberId={}, roomId={}", memberId, event.roomId(), e);
            }
        }
    }
```

- [ ] **Step 6: 엔드포인트를 뚫는다**

`ChatRoomController`:

```java
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomService.delete(id, user.getMemberId());
    }
```

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방장이 방을 삭제하고 접속 중인 멤버의 구독을 회수한다"
```

---

### Task 4: 강퇴와 차단 등록

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomMemberController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomKickTest.java`

**Interfaces:**
- Consumes: Task 1의 `ErrorCode.NOT_ROOM_OWNER`·`ROOM_KICKED`, Task 2의 `RoomLeftEvent(memberId, roomId, reason)`
- Produces:
  - `ChatRoomMemberService.kick(Long chatRoomId, Long targetMemberId, Long requesterId)` → `void`
  - `DELETE /api/chatrooms/{chatroomId}/members/{memberId}` → 204

**강퇴는 멤버십 행 삭제 + 차단 등록이다.** 프론트가 방을 고를 때마다 `join`을 부르므로, 행만 지우면 공개방에서는 우표 재클릭으로 즉시 복귀하고 잠긴 방도 쓰던 코드가 그대로 유효하다 — 차단 없이는 완전한 no-op이다.

`ChatRoomMemberService`는 `chatRoomBanRepository`를 **이미 주입받고 있다**(1단계의 join 차단 검사). 새 의존성이 필요 없다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.RoomAccess;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomKickTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 강퇴하면_멤버십이_사라지고_차단이_남는다() {
        Member owner = memberService.create("k1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("강퇴방", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThat(roomAccess.isMember(guest.getId(), room.getId())).isFalse();
        assertThat(chatRoomBanRepository.existsByChatRoomIdAndMemberId(room.getId(), guest.getId())).isTrue();
    }

    @Test
    void 강퇴당한_사람은_공개방에도_다시_못_들어온다() {
        Member owner = memberService.create("k2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("공개강퇴방", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }

    @Test
    void 강퇴당한_사람은_코드를_알아도_못_들어온다() {
        Member owner = memberService.create("k3-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k3-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("잠긴강퇴방", true, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode());
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }

    @Test
    void 방장이_아니면_강퇴할_수_없다() {
        Member owner = memberService.create("k4-owner@e.com", "1234", "주인");
        Member a = memberService.create("k4-a@e.com", "1234", "에이");
        Member b = memberService.create("k4-b@e.com", "1234", "비");
        ChatRoom room = chatRoomService.create("강퇴방4", false, owner.getId());
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), b.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 방장은_자기_자신을_강퇴할_수_없다() {
        Member owner = memberService.create("k5-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("강퇴방5", false, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), owner.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);
    }

    @Test
    void 멤버가_아닌_사람을_강퇴하면_거부된다() {
        Member owner = memberService.create("k6-owner@e.com", "1234", "주인");
        Member outsider = memberService.create("k6-out@e.com", "1234", "밖");
        ChatRoom room = chatRoomService.create("강퇴방6", false, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), outsider.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomKickTest*'`
Expected: 컴파일 실패 — `kick`이 없음

- [ ] **Step 3: 강퇴를 구현한다**

`leave`(멤버십 조회 → delete → 이벤트 발행)가 그대로 본이다.

```java
    /**
     * 멤버십 행만 지우면 강퇴가 무의미하다 — 프론트가 방 선택마다 join을 부르므로
     * 공개방은 우표 재클릭으로, 잠긴 방은 쓰던 코드로 즉시 복귀한다. 차단을 함께 남긴다.
     */
    @Transactional
    public void kick(Long chatRoomId, Long targetMemberId, Long requesterId) {
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
        if (chatRoom.isOwnedBy(targetMemberId)) {
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE);
        }

        Member target = memberService.getMemberById(targetMemberId);
        ChatRoomMember membership = chatRoomMemberRepository
                .findByMemberAndChatRoom(target, chatRoom)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_JOINED_ROOM));

        chatRoomMemberRepository.delete(membership);
        chatRoomBanRepository.save(new ChatRoomBan(chatRoomId, targetMemberId));
        eventPublisher.publishEvent(new RoomLeftEvent(targetMemberId, chatRoomId, ErrorCode.ROOM_KICKED));
    }
```

- [ ] **Step 4: `join`의 경합 창을 닫는다**

지금 `join`은 차단을 검사한 뒤 `saveAndFlush`로 멤버십을 넣는다. **강퇴 API가 생기는 순간** 그 사이에 커밋된 차단을 통과한 join이 멤버로 남을 수 있다. `chatroom_members`의 UNIQUE는 ban↔membership을 묶지 않아 이 경합을 막지 못한다.

`saveAndFlush` 직후 차단을 다시 확인하고, 걸렸으면 예외로 트랜잭션을 되돌린다.

```java
        ChatRoomMember saved;
        try {
            saved = chatRoomMemberRepository.saveAndFlush(chatRoomMember);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

        // 검사와 저장 사이에 강퇴가 커밋됐을 수 있다. 그러면 이 트랜잭션을 되돌린다.
        if (chatRoomBanRepository.existsByChatRoomIdAndMemberId(chatRoomId, memberId)) {
            throw new CustomException(ErrorCode.ROOM_BANNED);
        }
        return saved;
```

- [ ] **Step 5: 엔드포인트를 뚫는다**

`ChatRoomMemberController`는 base path가 `"api/chatrooms/{chatroomId}/members"`(앞 슬래시 없음)이고 경로변수가 `{chatroomId}`다. 기존 인자 없는 `@DeleteMapping`(자진 퇴장)과 충돌하지 않는다.

```java
        @DeleteMapping("/{memberId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void kick(
                        @PathVariable Long chatroomId,
                        @PathVariable Long memberId,
                        @AuthenticationPrincipal CustomUserDetails customUserDetails) {
                chatRoomMemberService.kick(chatroomId, memberId, customUserDetails.getMemberId());
        }
```

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 7: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방장이 멤버를 강퇴하고 재입장을 차단한다"
```

---

### Task 5: 차단 해제와 차단 목록

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomBanRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/BannedMemberResponse.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomBanListTest.java`

**Interfaces:**
- Consumes: Task 4의 `kick`
- Produces:
  - `ChatRoomBanRepository.deleteByChatRoomIdAndMemberId(Long chatRoomId, Long memberId)` → `void`
  - `ChatRoomBanRepository.findByChatRoomId(Long chatRoomId)` → `List<ChatRoomBan>`
  - `ChatRoomMemberService.unban(Long chatRoomId, Long targetMemberId, Long requesterId)` → `void`
  - `ChatRoomMemberService.getBannedMembers(Long chatRoomId, Long requesterId)` → `List<BannedMemberResponse>`
  - `GET /api/chatrooms/{id}/bans` → `List<BannedMemberResponse>`
  - `DELETE /api/chatrooms/{id}/bans/{memberId}` → 204

**차단 해제가 없으면 실수로 누른 강퇴가 영구가 된다.** 해제하려면 누가 차단됐는지 볼 수 있어야 하므로 목록 조회도 함께 만든다.

`ChatRoomBan.Id`에는 전인자 생성자가 없다(`@NoArgsConstructor` + `@Getter`만). 그래서 `deleteById(new ChatRoomBan.Id(...))`는 컴파일이 안 된다 — 파생 삭제 메서드를 쓴다.

두 엔드포인트는 base path가 `/api/chatrooms`인 `ChatRoomController`에 넣는다. `ChatRoomMemberController`의 base path(`api/chatrooms/{chatroomId}/members`)로는 `/bans`를 표현할 수 없다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.BannedMemberResponse;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomBanListTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 차단_목록에_강퇴당한_사람이_보인다() {
        Member owner = memberService.create("b1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("b1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("차단방", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        var banned = chatRoomMemberService.getBannedMembers(room.getId(), owner.getId());

        assertThat(banned).extracting(BannedMemberResponse::memberId).containsExactly(guest.getId());
        assertThat(banned).extracting(BannedMemberResponse::nickname).containsExactly("손님");
    }

    @Test
    void 차단을_해제하면_다시_들어올_수_있다() {
        Member owner = memberService.create("b2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("b2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("차단방2", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        chatRoomMemberService.unban(room.getId(), guest.getId(), owner.getId());

        assertThat(chatRoomBanRepository.existsByChatRoomIdAndMemberId(room.getId(), guest.getId())).isFalse();
        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void 방장이_아니면_차단_목록도_해제도_못_한다() {
        Member owner = memberService.create("b3-owner@e.com", "1234", "주인");
        Member other = memberService.create("b3-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("차단방3", false, owner.getId());
        chatRoomMemberService.join(other.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomMemberService.getBannedMembers(room.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
        assertThatThrownBy(() -> chatRoomMemberService.unban(room.getId(), other.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 차단되지_않은_사람을_해제해도_터지지_않는다() {
        Member owner = memberService.create("b4-owner@e.com", "1234", "주인");
        Member guest = memberService.create("b4-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("차단방4", false, owner.getId());

        assertThatCode(() -> chatRoomMemberService.unban(room.getId(), guest.getId(), owner.getId()))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomBanListTest*'`
Expected: 컴파일 실패 — `getBannedMembers`·`unban`·`BannedMemberResponse`가 없음

- [ ] **Step 3: 응답 DTO를 만든다**

```java
package com.example.springboot_realtimechat.dto;

import java.time.LocalDateTime;

public record BannedMemberResponse(Long memberId, String nickname, LocalDateTime bannedAt) {
}
```

- [ ] **Step 4: 리포지토리에 둘을 더한다**

```java
    void deleteByChatRoomIdAndMemberId(Long chatRoomId, Long memberId);

    List<ChatRoomBan> findByChatRoomId(Long chatRoomId);
```

- [ ] **Step 5: 서비스를 구현한다**

`ChatRoomBan`은 엔티티 참조가 아니라 raw `Long`을 들고 있어서 닉네임을 채우려면 회원을 따로 조회해야 한다. 차단 인원은 많지 않으므로 id 목록으로 한 번에 조회한다.

```java
    @Transactional
    public void unban(Long chatRoomId, Long targetMemberId, Long requesterId) {
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
        // 차단돼 있지 않아도 성공으로 둔다. 해제는 멱등이다.
        chatRoomBanRepository.deleteByChatRoomIdAndMemberId(chatRoomId, targetMemberId);
    }

    public List<BannedMemberResponse> getBannedMembers(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }

        List<ChatRoomBan> bans = chatRoomBanRepository.findByChatRoomId(chatRoomId);
        Map<Long, Member> members = memberRepository.findAllById(
                        bans.stream().map(ChatRoomBan::getMemberId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, m -> m));

        return bans.stream()
                .map(ban -> new BannedMemberResponse(
                        ban.getMemberId(),
                        members.containsKey(ban.getMemberId())
                                ? members.get(ban.getMemberId()).getNickname()
                                : null,
                        ban.getBannedAt()))
                .toList();
    }
```

`ChatRoomMemberService`에 `MemberRepository`를 주입한다.

- [ ] **Step 6: 엔드포인트를 뚫는다**

`ChatRoomController`에 넣는다.

```java
    @GetMapping("/{id}/bans")
    public List<BannedMemberResponse> getBannedMembers(@PathVariable Long id,
                                                       @AuthenticationPrincipal CustomUserDetails user) {
        return chatRoomMemberService.getBannedMembers(id, user.getMemberId());
    }

    @DeleteMapping("/{id}/bans/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unban(@PathVariable Long id, @PathVariable Long memberId,
                      @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomMemberService.unban(id, memberId, user.getMemberId());
    }
```

`ChatRoomController`는 이미 `ChatRoomMemberService`를 주입받고 있다.

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방장이 차단 목록을 보고 해제할 수 있게 한다"
```

---

### Task 6: 초대 코드 재발급

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/InviteCodeReissueTest.java`

**Interfaces:**
- Consumes: Task 1의 `ChatRoom.reissueInviteCode(String)`, Task 3의 `requireOwner`
- Produces:
  - `ChatRoomService.reissueInviteCode(Long chatRoomId, Long requesterId)` → `ChatRoom`
  - `POST /api/chatrooms/{id}/invite-code` → `ChatRoomResponse`

기존 `saveWithCode(String name, boolean isPrivate, Member owner)`는 **새 엔티티를 만드는 전용 private 메서드**라 재사용할 수 없다. 사전 확인 루프만 별도 헬퍼로 뽑는다.

**1단계에서 확인된 제약**: 코드 충돌은 저장 전에 `existsByInviteCode`로 미리 확인한다. JPA에서 제약 위반을 잡아 같은 트랜잭션에서 재시도하면 영속성 컨텍스트가 오염돼 `AssertionFailure`가 catch 밖으로 샌다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InviteCodeReissueTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 재발급하면_코드가_바뀌고_옛_코드는_안_통한다() {
        Member owner = memberService.create("r1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("r1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("재발급방", true, owner.getId());
        String oldCode = room.getInviteCode();

        ChatRoom reissued = chatRoomService.reissueInviteCode(room.getId(), owner.getId());

        assertThat(reissued.getInviteCode()).hasSize(12).isNotEqualTo(oldCode);
        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), oldCode))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 재발급된_새_코드로는_들어올_수_있다() {
        Member owner = memberService.create("r2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("r2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("재발급방2", true, owner.getId());

        String newCode = chatRoomService.reissueInviteCode(room.getId(), owner.getId()).getInviteCode();

        assertThat(chatRoomMemberService.join(guest.getId(), room.getId(), newCode)).isNotNull();
    }

    @Test
    void 방장이_아니면_재발급할_수_없다() {
        Member owner = memberService.create("r3-owner@e.com", "1234", "주인");
        Member other = memberService.create("r3-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("재발급방3", true, owner.getId());

        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 공개방은_재발급할_수_없다() {
        Member owner = memberService.create("r4-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("공개방", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*InviteCodeReissueTest*'`
Expected: 컴파일 실패 — `reissueInviteCode`가 없음

- [ ] **Step 3: 서비스를 구현한다**

```java
    @Transactional
    public ChatRoom reissueInviteCode(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);
        if (!chatRoom.isPrivate()) {
            throw new CustomException(ErrorCode.INVALID_INVITE_CODE);
        }

        chatRoom.reissueInviteCode(nextUnusedCode());
        return chatRoom;
    }

    /**
     * 저장 전에 중복을 미리 확인한다. 제약 위반을 잡아 같은 트랜잭션에서 재시도하면
     * 영속성 컨텍스트가 오염돼 다음 쿼리가 터진다.
     * ponytail: 사전 확인과 저장 사이 경합은 DB의 uk_chatrooms_invite_code가 최종 방어선이다.
     * 12자·32자 알파벳(약 60비트) 공간에서 확률이 무시할 수준이라 받아들인다.
     */
    private String nextUnusedCode() {
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (!chatRoomRepository.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
```

`saveWithCode`도 `nextUnusedCode`를 쓰도록 정리한다(중복 로직 제거).

- [ ] **Step 4: 엔드포인트를 뚫는다**

`GET /{id}` 단건 조회가 없으므로 응답을 직접 만든다. **요청자는 방장이므로 `joined`는 `true`다**(생성 시 첫 멤버로 등록되고, 방장은 나갈 수 없다).

```java
    @PostMapping("/{id}/invite-code")
    public ChatRoomResponse reissueInviteCode(@PathVariable Long id,
                                              @AuthenticationPrincipal CustomUserDetails user) {
        ChatRoom chatRoom = chatRoomService.reissueInviteCode(id, user.getMemberId());
        return ChatRoomResponse.from(chatRoom, user.getMemberId(), true);
    }
```

- [ ] **Step 5: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방장이 초대 코드를 다시 발급할 수 있게 한다"
```

---

### Task 7: 공개↔비공개 전환

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/dto/RoomPrivacyRequest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomPrivacyTest.java`

**Interfaces:**
- Consumes: Task 1의 `makePublic()`·`makePrivate(String)`, Task 6의 `nextUnusedCode()`
- Produces:
  - `ChatRoomService.setPrivate(Long chatRoomId, boolean isPrivate, Long requesterId)` → `ChatRoom`
  - `PATCH /api/chatrooms/{id}` — `{ "private": true | false }` → `ChatRoomResponse`

**`ChatRoomRequest`를 재사용하면 안 된다.** `name`이 `@NotBlank`라 전환에 이름을 강제하고, `isPrivate`가 원시 `boolean`이라 필드가 빠진 요청이 조용히 `false`(잠금 해제)가 된다. `Boolean` 래퍼 한 필드짜리 전용 DTO를 만든다.

**`@RequestBody(required = false)`로 받고 null을 명시적으로 거부한다.** `GlobalExceptionHandler`에 `HttpMessageNotReadableException` 핸들러가 없어서, 기본값으로 두면 본문이 없거나 깨졌을 때 500 + 스택트레이스가 CloudWatch로 나간다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.RoomAccess;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomPrivacyTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 공개로_바꾸면_코드가_사라지고_아무나_들어온다() {
        Member owner = memberService.create("p1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("p1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("전환방", true, owner.getId());

        ChatRoom changed = chatRoomService.setPrivate(room.getId(), false, owner.getId());

        assertThat(changed.isPrivate()).isFalse();
        assertThat(changed.getInviteCode()).isNull();
        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void 비공개로_바꾸면_새_코드가_생긴다() {
        Member owner = memberService.create("p2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("p2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("전환방2", false, owner.getId());

        ChatRoom changed = chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(changed.isPrivate()).isTrue();
        assertThat(changed.getInviteCode()).hasSize(12);
        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 비공개로_바꿔도_기존_멤버는_유지된다() {
        Member owner = memberService.create("p3-owner@e.com", "1234", "주인");
        Member guest = memberService.create("p3-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("전환방3", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(roomAccess.isMember(guest.getId(), room.getId())).isTrue();
    }

    @Test
    void 비공개를_거쳐_다시_비공개로_바꾸면_옛_코드가_부활하지_않는다() {
        Member owner = memberService.create("p4-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방4", true, owner.getId());
        String first = room.getInviteCode();

        chatRoomService.setPrivate(room.getId(), false, owner.getId());
        ChatRoom again = chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(again.getInviteCode()).isNotEqualTo(first);
    }

    @Test
    void 방장이_아니면_전환할_수_없다() {
        Member owner = memberService.create("p5-owner@e.com", "1234", "주인");
        Member other = memberService.create("p5-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("전환방5", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.setPrivate(room.getId(), true, other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 같은_상태로_바꾸면_비공개는_코드가_새로_생긴다() {
        Member owner = memberService.create("p6-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방6", true, owner.getId());
        String before = room.getInviteCode();

        ChatRoom same = chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(same.isPrivate()).isTrue();
        assertThat(same.getInviteCode()).isNotEqualTo(before);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomPrivacyTest*'`
Expected: 컴파일 실패 — `setPrivate`가 없음

- [ ] **Step 3: 요청 DTO를 만든다**

```java
package com.example.springboot_realtimechat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 원시 boolean을 쓰지 않는다. 필드가 빠진 요청이 조용히 false(잠금 해제)가 되면 안 된다.
 */
@Getter
@Setter
public class RoomPrivacyRequest {
    @JsonProperty("private")
    private Boolean isPrivate;
}
```

- [ ] **Step 4: 서비스를 구현한다**

```java
    @Transactional
    public ChatRoom setPrivate(Long chatRoomId, boolean isPrivate, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);

        if (isPrivate) {
            // 전환할 때마다 새 코드를 뽑는다. 옛 코드가 부활하면 유출된 코드가 다시 유효해진다.
            chatRoom.makePrivate(nextUnusedCode());
        } else {
            chatRoom.makePublic();
        }
        return chatRoom;
    }
```

- [ ] **Step 5: 엔드포인트를 뚫는다**

```java
    @PatchMapping("/{id}")
    public ChatRoomResponse setPrivate(@PathVariable Long id,
                                       @RequestBody(required = false) RoomPrivacyRequest request,
                                       @AuthenticationPrincipal CustomUserDetails user) {
        if (request == null || request.getIsPrivate() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        ChatRoom chatRoom = chatRoomService.setPrivate(id, request.getIsPrivate(), user.getMemberId());
        return ChatRoomResponse.from(chatRoom, user.getMemberId(), true);
    }
```

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 7: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방장이 방의 공개 여부를 바꿀 수 있게 한다"
```

---

### Task 8: 프론트 — API와 삭제·강퇴 통지 처리

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: Task 3~7의 엔드포인트
- Produces:
  - `deleteChatRoom(token: string, chatroomId: string): Promise<void>`
  - `reissueInviteCode(token: string, chatroomId: string): Promise<BackendChatRoom>`
  - `setRoomPrivacy(token: string, chatroomId: string, isPrivate: boolean): Promise<BackendChatRoom>`
  - `kickMember(token: string, chatroomId: string, memberId: string): Promise<void>`
  - `getRoomBans(token: string, chatroomId: string): Promise<BackendBannedMember[]>`
  - `unbanMember(token: string, chatroomId: string, memberId: string): Promise<void>`
  - `BackendBannedMember { memberId: number; nickname: string | null; bannedAt: string }`

**`onAuthzError`가 지금 `channels`를 건드리지 않는다.** 그래서 방이 사라져도 랜딩에 클릭 가능한 유령 우표가 남는다. 이번에 그것까지 정리한다.

정리해야 할 것은 넷이다: `channels`(=`refreshRooms`), `joinedRoomsRef.delete`, `unread`·`roomLastRead`의 그 방 키, `selectedChannelId`(비우면 기존 이펙트가 stomp 구독 해제와 `onlineMemberIds`까지 처리한다).

- [ ] **Step 1: API 함수 6개를 더한다**

`frontend/src/lib/api.ts`. 기존 `joinChatRoom`·`createChatRoom`의 형태를 따른다.

```ts
export interface BackendBannedMember {
  memberId: number;
  nickname: string | null;
  bannedAt: string;
}

export async function deleteChatRoom(token: string, chatroomId: string) {
  return request<void>(`/api/chatrooms/${chatroomId}`, { method: 'DELETE' }, token);
}

export async function reissueInviteCode(token: string, chatroomId: string) {
  return request<BackendChatRoom>(`/api/chatrooms/${chatroomId}/invite-code`, { method: 'POST' }, token);
}

export async function setRoomPrivacy(token: string, chatroomId: string, isPrivate: boolean) {
  return request<BackendChatRoom>(`/api/chatrooms/${chatroomId}`, {
    method: 'PATCH',
    body: JSON.stringify({ private: isPrivate }),
  }, token);
}

export async function kickMember(token: string, chatroomId: string, memberId: string) {
  return request<void>(`/api/chatrooms/${chatroomId}/members/${memberId}`, { method: 'DELETE' }, token);
}

export async function getRoomBans(token: string, chatroomId: string) {
  return request<BackendBannedMember[]>(`/api/chatrooms/${chatroomId}/bans`, {}, token);
}

export async function unbanMember(token: string, chatroomId: string, memberId: string) {
  return request<void>(`/api/chatrooms/${chatroomId}/bans/${memberId}`, { method: 'DELETE' }, token);
}
```

- [ ] **Step 2: `onAuthzError`에 새 코드를 분기한다**

`App.tsx`의 `onAuthzError`를 연다. 지금은 `ROOM_MEMBERSHIP_REVOKED`에서만 토스트를 억제하고 `joinedRoomsRef`·`selectedChannelId`만 정리한다.

- `ROOM_DELETED`·`ROOM_KICKED`는 **토스트를 띄운다**(서버 문구를 그대로 쓴다 — 이미 사용자용 문구다).
- 세 코드 모두 방 키 정리를 한다: `joinedRoomsRef.delete(roomId)`, `unread`·`roomLastRead`에서 그 키 제거, 보고 있던 방이면 `selectedChannelId` 비우기.
- 그다음 `refreshRooms(token)`을 부른다. **실패해도 사용자에게 오류를 띄우지 않는다** — 서버 상태는 이미 바뀐 뒤라 재시도가 의미 없다. 기존 두 호출 지점이 그렇게 하고 있다(`console.error`로 삼킨다).

- [ ] **Step 3: 검증한다**

Run: `cd frontend && npm run lint && npm run build && npx vitest run`
Expected: 전부 exit 0

- [ ] **Step 4: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 방 삭제·강퇴 통지를 받아 목록과 상태를 정리한다"
```

---

### Task 9: 프론트 — 방장 패널(코드 재발급·공개여부·방 삭제)

**Files:**
- Modify: `frontend/src/components/ChannelLanding.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: Task 8의 `deleteChatRoom`·`reissueInviteCode`·`setRoomPrivacy`
- Produces: 없음(UI)

**작업 전에 `ChannelLanding.tsx`의 아래 지점을 먼저 읽어라.** 조사 시점 행 번호이므로 그대로 믿지 말고 조건식·구조로 찾는다.

| 지점 | 조사 시점 위치 | 무엇인가 |
|---|---|---|
| 확대 액션 패널 | 546~598 | 방장 액션이 들어갈 영역 |
| 분기 조건 | 555 | `focused.locked && !focused.joined` |
| "내 초대 코드" | 581~588 | else 쪽, `focused.owner && focused.inviteCode` |
| "입장하기" | 589~594 | else 쪽 |
| 확대 오버레이 | 509 | `focused`가 null이면 언마운트 |
| 딤 조건 | 447 | `focusedId`가 남으면 다른 우표가 blur+반투명으로 굳는다 |
| 패널 top 계산 | 549 | 우표 아래 고정값 — 액션이 늘면 화면 밖으로 밀린다 |
| focusedId 초기화 이펙트 | 232~235 | 지금은 `joinCode`·`joinError`만 비운다 |
| `InviteCode` 컴포넌트 | — | `copied` state가 `code` 변경에 반응하지 않는다 |

방장 액션은 **else 쪽에 `focused.owner &&` 조건으로** 들어간다 — then 분기(코드 입력)와 상호배타라 충돌하지 않는다.

**주의할 것 넷:**

1. **방 삭제 성공 시 `setFocusedId(null)`을 목록 갱신보다 먼저 부른다.** 순서를 바꾸면 `focused`가 null이 되어 확대 오버레이가 언마운트되는데 `focusedId`는 남아, 남은 우표 전부가 blur+반투명으로 굳고 배경 딤이 사라져 클릭으로 닫을 수 없다(ESC만 동작).
2. **파괴적 동작에는 확인 단계를 둔다.** 그 확인 state는 `focusedId`가 바뀔 때 초기화되는 기존 이펙트(지금은 `joinCode`·`joinError`만 비운다)에 함께 넣는다. 안 넣으면 A방의 "삭제할까요?"가 B방 우표로 따라간다.
3. **`InviteCode`의 `copied` state가 `code` prop 변경으로 초기화되지 않는다.** 재발급 후에도 "복사됨"이 남아 새 코드를 복사한 것처럼 보인다. `code`를 의존성으로 하는 초기화를 넣는다.
4. **액션 패널에 스크롤·최대 높이가 없다.** 패널 `top`이 우표 아래 고정 계산값이라, 액션이 늘면 낮은 뷰포트에서 화면 밖으로 밀린다. `max-height` + `overflow-y: auto`를 준다.

- [ ] **Step 1: 방장 액션 세 개를 붙인다**

`focused.owner`일 때만 보인다.

- **코드 재발급** — 잠긴 방일 때만. 성공하면 `refreshRooms` 후 새 코드가 그 자리에 보인다.
- **공개↔비공개 전환** — 현재 상태의 반대로 바꾸는 버튼 하나. 비공개로 바꾸면 새 코드가 나온다.
- **방 삭제** — 확인 단계를 거친다.

- [ ] **Step 2: `App.tsx`에 핸들러를 배선한다**

`ChannelLanding`에 `onReissueCode`·`onSetPrivacy`·`onDeleteRoom`을 넘긴다. 각 핸들러는 API 호출 후 `refreshRooms`를 부르고, 실패는 `toUserMessage`로 토스트한다.

방 삭제 핸들러는 지운 방이 보고 있던 방이면 `selectedChannelId`를 비우고 방 키들을 정리한다(Task 8이 만든 정리 로직을 재사용한다).

- [ ] **Step 3: 검증한다**

Run: `cd frontend && npm run lint && npm run build && npx vitest run`
Expected: 전부 exit 0

- [ ] **Step 4: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 확대한 우표에서 방장이 방을 운영할 수 있게 한다"
```

---

### Task 10: 프론트 — 강퇴와 차단 해제 UI

**Files:**
- Modify: `frontend/src/components/ChatArea.tsx`
- Modify: `frontend/src/components/ChannelLanding.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: Task 8의 `kickMember`·`getRoomBans`·`unbanMember`
- Produces: 없음(UI)

**작업 전에 `ChatArea.tsx`의 아래 지점을 먼저 읽어라.** 조사 시점 행 번호이므로 구조로 찾는다.

| 지점 | 조사 시점 위치 | 무엇인가 |
|---|---|---|
| 헤더 버튼 그룹 | 336~375 | 방장 진입점을 둔다면 여기 |
| 참가자 목록 항목 | 426~451 | `li` 내용물 전체가 이미 하나의 `button`(427) |

**참가자 목록의 `li` 내용물 전체가 이미 하나의 `button`이다.** 그 안에 강퇴 버튼을 넣으면 button 중첩이라 클릭이 프로필 열기로 새거나 렌더가 깨진다. 프로필 버튼과 강퇴 버튼을 **형제로** 배치하도록 그 `li`를 재구성한다.

**강퇴 대상 식별자에 주의한다.** `getRoomMemberProfiles`가 돌려주는 `RoomMemberProfile.id`는 **회원 id**이고, 원본 `BackendChatRoomMember.id`는 **멤버십 행 id**다. 잘못 고르면 엉뚱한 사람이 나간다.

- [ ] **Step 1: 참가자 목록에 강퇴를 붙인다**

`ChatArea`의 참가자 항목을 프로필 버튼 + 강퇴 버튼 형제 구조로 바꾼다. 강퇴 버튼은 **현재 사용자가 그 방의 방장일 때, 자기 자신이 아닌 대상에만** 보인다. 확인 단계를 거친다.

`ChatArea`가 `channel.owner`를 읽게 되므로, `App.tsx`의 `activeChannel` 폴백 객체가 `locked`·`joined`·`owner`를 채우고 있는지 확인한다(PR #100이 채워 뒀다).

- [ ] **Step 2: 차단 목록과 해제를 붙인다**

방장이 확대 우표의 방장 패널에서 차단 목록을 열고 해제할 수 있게 한다. 목록은 열 때 `getRoomBans`로 가져온다. 비어 있으면 "차단된 사람이 없어요"를 보여준다.

- [ ] **Step 3: 검증한다**

Run: `cd frontend && npm run lint && npm run build && npx vitest run`
Expected: 전부 exit 0

- [ ] **Step 4: 실제 브라우저로 확인한다**

로컬 MySQL에 throwaway DB를 만들고 백엔드를 띄운다(`JWT_SECRET` 필수). 접속 정보는 `src/main/resources/application.yaml`에 있다(`root`/`1111`, `localhost:3306`).

```bash
/usr/local/mysql/bin/mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS rm_check; CREATE DATABASE rm_check CHARACTER SET utf8mb4;"
JWT_SECRET="local-verify-secret-32bytes-minimum-length-ok" \
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/rm_check" ./gradlew bootRun
```

시드 계정은 `demo@demo.com` / `guest@demo.com`, 비밀번호 `demo1234`다. **로그인 UI가 소셜 전용이라 데모 계정은 UI로 못 들어간다** — `localStorage['chat_auth_session']`에 `{token, user:{id, email, displayName, avatar, onboarded}}`를 직접 심는다(`displayName`·`avatar`를 빠뜨리면 `Avatar`가 터진다).

이 환경은 **스크린샷이 검게 나온다**(Canvas rAF). DOM 상태를 세는 방식으로 확인한다. 우표 클릭은 `.click()`이 아니라 `dispatchEvent(new MouseEvent('click',{bubbles:true}))`로 해야 React 핸들러가 돈다.

확인할 것:
- 방장에게만 방장 액션이 보이고, 비방장에게는 안 보인다
- 코드 재발급 후 화면의 코드가 바뀌고 "복사됨"이 남아 있지 않다
- 공개로 바꾸면 코드 영역이 사라지고 자물쇠가 없어진다
- 강퇴하면 대상 화면이 랜딩으로 돌아가고 **"방에서 내보내졌어요"가 토스트로 뜬다**(아무 설명 없이 튕기면 실패다)
- 방을 삭제하면 다른 멤버 화면에서 그 우표가 사라지고 "방이 삭제되었어요"가 뜬다
- 차단 해제 후 그 사람이 다시 들어올 수 있다

**확인한 것만 보고서에 적는다. 못 한 것은 못 했다고 적는다.**

- [ ] **Step 5: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 방장이 참가자를 강퇴하고 차단을 해제할 수 있게 한다"
```

---

## 완료 기준

- `./gradlew test` 전부 통과
- `cd frontend && npm run lint && npm run build && npx vitest run` 전부 통과
- **실제 MySQL 부팅** — V1~V7 적용 후 `ddl-auto: validate` 통과(2단계는 마이그레이션을 추가하지 않지만 엔티티에 변경 메서드를 더하므로 확인한다)
- **강퇴·삭제 통지의 코드값을 직접 확인** — `ROOM_KICKED`·`ROOM_DELETED`가 실제로 나가는지. `ROOM_MEMBERSHIP_REVOKED`가 나가면 프론트가 토스트를 억제해 **통지 미도달과 구분되지 않는다**
- 시드 방(`created_by` NULL)에 방장 API 5종 호출 → 전부 403이고 500이 아님

## 다음 단계로 넘기는 것

방장 위임(주인 없는 방을 되살리는 유일한 길), 삭제 복원 UI, `deleted_at` purge 배치, 멀티 인스턴스 구독 회수(외부 브로커 릴레이 선행), 방 이름 변경, 방 이미지의 접근 제어(S3 공개 URL이라 삭제·강퇴 후에도 URL 소지자는 계속 읽는다).
