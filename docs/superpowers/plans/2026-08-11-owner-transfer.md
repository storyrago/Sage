# 방장 위임 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주인이 남은 멤버 중 후계자를 지정하고 그 방을 나갈 수 있게 한다.

**Architecture:** 엔드포인트 하나(`PATCH /api/chatrooms/{id}/owner`)와 엔티티 변경 메서드 하나(`transferOwnership`)면 끝난다. **위임 후 나가기는 따로 만들지 않는다** — `created_by`가 바뀌는 순간 기존 `leave`의 `OWNER_CANNOT_LEAVE` 가드가 자연히 통과한다.

**Tech Stack:** Spring Boot 3, Spring Data JPA, JUnit 5 + AssertJ, React 19 + TypeScript

**설계 문서:** [`docs/superpowers/specs/2026-08-11-owner-transfer-design.md`](../specs/2026-08-11-owner-transfer-design.md)

## Global Constraints

- **Flyway 마이그레이션을 추가하지 않는다.** `chatrooms.created_by`는 V7에 이미 있다.
- **`ChatRoomService`는 클래스 레벨이 `@Transactional(readOnly = true)`다.** 새 변경 메서드에 `@Transactional`을 빠뜨리면 더티체킹 UPDATE가 안 나간다. 이 애노테이션이 유일한 저장 트리거다.
- **방장 판정은 기존 private 헬퍼 `requireOwner(ChatRoom, Long)`를 그대로 쓴다**(`ChatRoomService:131`). 인라인으로 새로 쓰지 마라 — 2단계에서 `ChatRoomMemberService`의 인라인 중복 3개가 `created_by NULL`에 무방비였다.
- **멤버십 확인은 `chatRoomMemberRepository.existsActiveMembership(memberId, chatRoomId)`를 쓴다.** `existsByMemberIdAndChatRoomId`는 삭제 필터가 없어 삭제된 방에서 멤버십이 살아 있는 것으로 판정된다.
- **`ChatRoomResponse.from(ChatRoom, Long requesterId, boolean joined)` 3인자 팩토리만 쓴다.** 단일 인자를 되살리면 초대 코드가 전원에게 나간다.
- **초대 코드는 요청·응답 본문에만.** URL·쿼리·로그에 넣지 않는다.
- 커밋 메시지·주석에 배경 서사("누락", "핫픽스", "깨져 있었다")를 쓰지 않는다.
- 백엔드 검증은 `./gradlew test`, 프론트는 `cd frontend && npm run lint && npm run build && npx vitest run`.
- 브랜치는 `feat/owner-transfer`(`0bb5e57`에서 분기, 설계 문서 커밋 `aabf576`이 이미 올라가 있다).

## 이번 작업에 없는 것

계정 탈퇴 시 후계자 지정(현행 자동 동결 유지), 주인 없는 방 되살리기, 위임 실시간 통지, 위임 이력.

---

### Task 1: 위임 API

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/ChatRoom.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/OwnerTransferRequest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/OwnerTransferTest.java`

**Interfaces:**
- Consumes: `ChatRoom.isOwnedBy`, `ChatRoomService.requireOwner`(private, 이미 있음), `chatRoomMemberRepository.existsActiveMembership`
- Produces:
  - `ChatRoom.transferOwnership(Member newOwner)` → `void`
  - `ChatRoomService.transferOwnership(Long chatRoomId, Long newOwnerId, Long requesterId)` → `ChatRoom`
  - `PATCH /api/chatrooms/{id}/owner` → `ChatRoomResponse`

`ChatRoomService`는 이미 `chatRoomRepository`·`chatRoomMemberRepository`·`memberRepository`·`inviteCodeGenerator`·`eventPublisher`를 주입받고 있다. **새 의존성이 필요 없다.**

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/room/OwnerTransferTest.java`

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OwnerTransferTest {

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
    void 주인이_멤버에게_방장을_넘긴다() {
        Member owner = memberService.create("t1-owner@e.com", "1234", "주인");
        Member next = memberService.create("t1-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방", false, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), null);

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.isOwnedBy(next.getId())).isTrue();
        assertThat(reloaded.isOwnedBy(owner.getId())).isFalse();
    }

    @Test
    void 위임하면_옛_주인이_방을_나갈_수_있다() {
        Member owner = memberService.create("t2-owner@e.com", "1234", "주인");
        Member next = memberService.create("t2-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방2", false, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), null);

        // 위임 전에는 막힌다
        assertThatThrownBy(() -> chatRoomMemberService.leave(owner.getId(), room.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        assertThatCode(() -> chatRoomMemberService.leave(owner.getId(), room.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void 위임하면_방장_권한이_실제로_옮겨간다() {
        Member owner = memberService.create("t3-owner@e.com", "1234", "주인");
        Member next = memberService.create("t3-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방3", true, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), room.getInviteCode());

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        // 새 주인은 방장 API를 쓸 수 있다
        assertThatCode(() -> chatRoomService.reissueInviteCode(room.getId(), next.getId()))
                .doesNotThrowAnyException();
        // 옛 주인은 못 쓴다
        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 주인이_아니면_위임할_수_없다() {
        Member owner = memberService.create("t4-owner@e.com", "1234", "주인");
        Member a = memberService.create("t4-a@e.com", "1234", "에이");
        Member b = memberService.create("t4-b@e.com", "1234", "비");
        ChatRoom room = chatRoomService.create("위임방4", false, owner.getId());
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), b.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 주인_없는_방은_아무도_위임할_수_없다() {
        Member a = memberService.create("t5-a@e.com", "1234", "에이");
        Member b = memberService.create("t5-b@e.com", "1234", "비");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), b.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 멤버가_아닌_사람에게는_위임할_수_없다() {
        Member owner = memberService.create("t6-owner@e.com", "1234", "주인");
        Member outsider = memberService.create("t6-out@e.com", "1234", "밖");
        ChatRoom room = chatRoomService.create("위임방6", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), outsider.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 자기_자신에게는_위임할_수_없다() {
        Member owner = memberService.create("t7-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("위임방7", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), owner.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 삭제된_방은_위임할_수_없다() {
        Member owner = memberService.create("t8-owner@e.com", "1234", "주인");
        Member next = memberService.create("t8-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("삭제된방", false, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), null);
        chatRoomService.delete(room.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 잠긴_방을_위임해도_잠금과_코드는_그대로다() {
        Member owner = memberService.create("t9-owner@e.com", "1234", "주인");
        Member next = memberService.create("t9-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("잠긴위임방", true, owner.getId());
        String code = room.getInviteCode();
        chatRoomMemberService.join(next.getId(), room.getId(), code);

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.isPrivate()).isTrue();
        assertThat(reloaded.getInviteCode()).isEqualTo(code);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*OwnerTransferTest*'`
Expected: 컴파일 실패 — `ChatRoomService.transferOwnership`이 없음

- [ ] **Step 3: 엔티티에 변경 메서드를 더한다**

`ChatRoom`의 `reissueInviteCode` 아래에 붙인다.

```java
    /** 잠금·코드는 건드리지 않는다. 방의 공개 여부와 주인은 독립적이다. */
    public void transferOwnership(Member newOwner) {
        this.createdBy = newOwner;
    }
```

- [ ] **Step 4: 요청 DTO를 만든다**

`src/main/java/com/example/springboot_realtimechat/dto/OwnerTransferRequest.java`

```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

/** 요청자는 언제나 JWT에서 온다. 여기 담기는 것은 넘겨받을 사람뿐이다. */
@Getter
@Setter
public class OwnerTransferRequest {
    private Long memberId;
}
```

- [ ] **Step 5: 서비스를 구현한다**

`ChatRoomService`에 더한다. **기존 private `requireOwner`를 그대로 쓴다.**

```java
    @Transactional
    public ChatRoom transferOwnership(Long chatRoomId, Long newOwnerId, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);

        if (newOwnerId.equals(requesterId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        // 차단된 사람은 멤버가 아니므로 이 검사에서 함께 걸린다.
        if (!chatRoomMemberRepository.existsActiveMembership(newOwnerId, chatRoomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }

        Member newOwner = memberRepository.findById(newOwnerId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        chatRoom.transferOwnership(newOwner);
        return chatRoom;
    }
```

- [ ] **Step 6: 엔드포인트를 뚫는다**

`ChatRoomController`. 기존 `@PatchMapping("/{id}")`(공개여부 전환) 아래에 붙인다.

**`@RequestBody(required = false)`로 받고 null을 명시적으로 거부한다.** 응답의 `joined`는 `true`다 — 옛 주인은 위임 후에도 그 방의 멤버다(나가는 것은 별개 동작이다).

```java
    @PatchMapping("/{id}/owner")
    public ChatRoomResponse transferOwnership(@PathVariable Long id,
                                              @RequestBody(required = false) OwnerTransferRequest request,
                                              @AuthenticationPrincipal CustomUserDetails user) {
        if (request == null || request.getMemberId() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        ChatRoom chatRoom = chatRoomService.transferOwnership(id, request.getMemberId(), user.getMemberId());
        return ChatRoomResponse.from(chatRoom, user.getMemberId(), true);
    }
```

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방장이 다른 멤버에게 방장을 넘길 수 있게 한다"
```

---

### Task 2: 프론트 — 방장 넘기기 UI

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/components/ChannelLanding.tsx`

**Interfaces:**
- Consumes: Task 1의 `PATCH /api/chatrooms/{id}/owner`
- Produces: `transferOwnership(token: string, chatroomId: string, memberId: string): Promise<BackendChatRoom>`

**작업 전에 `ChannelLanding.tsx`에서 확인할 것** — 2단계가 만든 구조를 그대로 따른다. 행 번호가 아니라 구조로 찾아라.

| 지점 | 무엇인가 |
|---|---|
| 확대 액션 패널의 방장 블록 | `focused.owner &&` 조건. 코드 재발급·공개여부 전환·방 삭제·차단 목록이 여기 있다 |
| `ownerBusy` state | 방장 액션 진행 중 다른 버튼을 막는 패턴 |
| `focusedId` 변경 초기화 이펙트 | `joinCode`·`joinError`·`showBans`·`bans`·`bansError`·`confirmDelete`를 비운다 |
| `token` prop | 2단계에서 추가됨. `getRoomBans`·`unbanMember`를 직접 부른다 |
| `InviteCode` 컴포넌트 | 코드 표시·복사 |

**주의할 것 셋:**

1. **되돌릴 수 없는 동작이다.** 넘기는 순간 내 방장 권한이 사라지고, 되돌리려면 새 주인이 다시 넘겨줘야 한다. 확인 단계에 **그 사실을 문구로 적어라** — "정말 넘길까요?"만으로는 되돌릴 수 없다는 게 전달되지 않는다.
2. **넘길 대상이 없을 때를 먼저 처리하라.** 멤버가 자기 혼자면 "넘길 수 있는 참가자가 없어요"를 보여준다. 버튼만 두고 눌렀을 때 빈 목록이 뜨면 왜 안 되는지 알 수 없다.
3. **새 state는 `focusedId` 초기화 이펙트에 함께 넣어라.** 안 넣으면 A방에서 고른 후계자·확인 상태가 B방 우표로 따라간다. 2단계에서 같은 함정을 이미 밟았다.

- [ ] **Step 1: API 함수를 더한다**

`frontend/src/lib/api.ts`. 기존 `setRoomPrivacy`의 형태를 따른다.

```ts
export async function transferOwnership(token: string, chatroomId: string, memberId: string) {
  return request<BackendChatRoom>(`/api/chatrooms/${chatroomId}/owner`, {
    method: 'PATCH',
    body: JSON.stringify({ memberId: Number(memberId) }),
  }, token);
}
```

- [ ] **Step 2: 방장 패널에 "방장 넘기기"를 더한다**

`focused.owner`일 때만 보인다. 열면 `getRoomMemberProfiles(token, focused.id)`로 참가자를 가져와 **자기 자신을 뺀 목록**을 보여준다.

- 대상이 없으면 "넘길 수 있는 참가자가 없어요"
- 대상을 고르면 확인 단계로 간다. 문구에 **되돌릴 수 없다는 것**을 적는다
- 확인하면 `transferOwnership`을 부르고, 성공하면 `refreshRooms`를 부른다. 그 방의 `owner`가 `false`가 되어 방장 패널이 사라진다
- 실패는 `toUserMessage`로 인라인 표시(토스트가 아니라 — 어느 방에서 실패했는지 알아야 한다)

**대상 식별자에 주의하라.** `getRoomMemberProfiles`가 돌려주는 `RoomMemberProfile.id`는 **회원 id**다(`BackendChatRoomMember.memberId`를 매핑한 값). 멤버십 행 id가 아니다.

- [ ] **Step 3: 검증한다**

Run: `cd frontend && npm run lint && npm run build && npx vitest run`
Expected: 전부 exit 0

- [ ] **Step 4: 실제로 확인한다**

로컬 MySQL에 throwaway DB를 만들고 백엔드를 띄운다. 접속 정보는 `src/main/resources/application.yaml`에 있다(`root`/`1111`, `localhost:3306`). 포트 8080이 점유돼 있으면 `SERVER_PORT`로 바꾼다.

```bash
/usr/local/mysql/bin/mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS ot_check; CREATE DATABASE ot_check CHARACTER SET utf8mb4;"
JWT_SECRET="local-verify-secret-32bytes-minimum-length-ok" \
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/ot_check" ./gradlew bootRun
```

시드 계정은 `demo@demo.com`·`guest@demo.com`, 비밀번호 `demo1234`다. **로그인 UI가 소셜 전용이라 데모 계정은 UI로 못 들어간다** — `localStorage['chat_auth_session']`에 `{token, user:{id, email, displayName, avatar, onboarded}}`를 직접 심는다(`displayName`·`avatar`를 빠뜨리면 `Avatar`가 터진다).

이 환경은 **스크린샷이 검게 나온다**(Canvas rAF). DOM 상태를 세는 방식으로 확인한다. 우표 클릭은 `.click()`이 아니라 `dispatchEvent(new MouseEvent('click',{bubbles:true}))`로 해야 React 핸들러가 돈다.

확인할 것:
- 방장에게만 "방장 넘기기"가 보인다
- 참가자 목록에 **자기 자신이 없다**
- 넘긴 뒤 그 방의 방장 패널이 사라진다
- 새 주인 세션으로 바꾸면 그 방에 방장 패널이 보인다
- 옛 주인이 그 방을 나갈 수 있다

**REST로 교차 확인하는 편이 빠른 항목은 curl을 써라.** 특히 "권한이 실제로 옮겨갔는지"는 옛 주인·새 주인 각각으로 `POST /{id}/invite-code`를 쳐보면 한 번에 확인된다.

**확인한 것만 보고서에 적는다. 못 한 것은 못 했다고 적는다.**

- [ ] **Step 5: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 방장이 다른 참가자에게 방장을 넘길 수 있게 한다"
```

---

## 완료 기준

- `./gradlew test` 전부 통과
- `cd frontend && npm run lint && npm run build && npx vitest run` 전부 통과
- **위임 후 옛 주인이 방을 나갈 수 있는지** 실제로 확인 — 이 작업의 목적이 그것이다
- **권한이 실제로 옮겨갔는지** 옛 주인·새 주인 양쪽으로 방장 API를 쳐서 확인
- 주인 없는 방(시드 방)에서 위임이 403이고 500이 아닌지

## 다음 단계로 넘기는 것

계정 탈퇴 시 후계자 지정, 주인 없는 방 되살리기, 위임 실시간 통지(새 주인이 다음 목록 갱신까지 모른다), 위임 이력 기록.
