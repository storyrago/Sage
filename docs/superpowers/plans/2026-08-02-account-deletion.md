# 회원 탈퇴 시 메시지 익명화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 탈퇴가 답장 외래키에 막히지 않게 하고, 대화를 보존하면서 개인정보를 지운다.

**Architecture:** 탈퇴 시 그 회원의 메시지를 지우는 대신 `member_id`를 `NULL`로 만든다. 메시지가 남으므로 답장이 가리키는 원본이 사라지지 않고 `fk_messages_reply`가 걸리지 않는다. 작성자 없는 메시지를 조회·표시·인가할 수 있도록 조회 쿼리와 DTO, 소유권 검사, 프론트 변환을 함께 맞춘다.

**Tech Stack:** Spring Boot 4.0.5, Spring Data JPA, JUnit 5, H2, React + TypeScript

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-08-02-account-deletion-design.md`
- **마이그레이션을 추가하지 않는다.** `messages.member_id`는 이미 nullable이고(`V1__baseline.sql:27`), `fk_messages_reply`의 `ON DELETE` 정책도 건드리지 않는다
- **탈퇴자의 메시지를 지우지 않는다.** 작성자 참조만 끊는다
- **서버는 사실만 내려보낸다.** `MessageResponse`의 `memberId`·`nickname`·`profileImageUrl`은 작성자가 없으면 `null`이다. "삭제된 사용자" 문구는 프론트가 만든다
- 익명화된 메시지는 아무도 수정·삭제할 수 없다 → `NOT_MESSAGE_OWNER`(403). NPE로 새지 않는다
- 탈퇴 시 정리 대상은 **프로필 사진뿐**이다. 메시지가 남으므로 메시지 이미지는 참조가 유지된다
- `MemberDeletedEvent`(구독 회수)를 이미지 이벤트보다 **먼저** 발행하는 순서를 유지한다
- 새 의존성 없음. 새 이벤트 타입 없음
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm run lint && npm test && npm run build`
- 브랜치: develop에서 `feat/account-deletion-anonymize`를 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사를 넣지 않는다

## File Structure

| 파일 | 변경 |
|---|---|
| `repository/MessageRepository.java` | `deleteByMember` 제거, 작성자를 비우는 `@Modifying` 쿼리 추가, 조회 2개를 `LEFT JOIN FETCH`로 |
| `service/MemberService.java` | `delete()`가 메시지를 익명화하고 프로필 URL만 정리 대상으로 발행 |
| `service/MessageService.java` | `update`·`delete`의 소유권 검사 널 가드 |
| `dto/MessageResponse.java` | 작성자 없음을 `null`로 표현 |
| `frontend/src/lib/api.ts` | `toMessage`가 작성자 없음을 다룬다 |
| `frontend/src/components/ChatArea.tsx` | 익명 작성자는 프로필 열기를 걸지 않는다 |

---

### Task 1: 작성자 없는 메시지를 조회·표현할 수 있게 한다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/dto/MessageResponse.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MessageService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/message/AnonymousAuthorTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces: 작성자가 `null`인 `Message`를 다룰 수 있는 조회·DTO·인가 경로. Task 2의 탈퇴 익명화가 이것을 전제한다

**배경:** 지금은 작성자 없는 메시지가 존재할 수 없어서 어디에도 널 처리가 없다. Task 2가 그런 메시지를 만들기 전에, 조회에서 빠지지 않고 DTO에서 NPE가 나지 않으며 인가가 정상 거부하도록 먼저 맞춘다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git checkout develop && git pull && git checkout -b feat/account-deletion-anonymize
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/message/AnonymousAuthorTest.java`:

```java
package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 탈퇴한 회원의 메시지는 작성자 없이 남는다. 조회에서 빠지거나 NPE가 나면 안 된다.
@SpringBootTest
@Transactional
class AnonymousAuthorTest {

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired EntityManager entityManager;

    @MockitoBean S3Service s3Service;

    private Member reader;
    private Long roomId;
    private Long anonymousMessageId;

    @BeforeEach
    void setUp() {
        Member author = memberService.create("anon-author@e.com", "1234", "작성자");
        reader = memberService.create("anon-reader@e.com", "1234", "읽는이");
        ChatRoom room = chatRoomService.create("익명방");
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId);
        chatRoomMemberService.join(reader.getId(), roomId);

        Message message = messageService.create("남는 메시지", null, author.getId(), roomId, null);
        anonymousMessageId = message.getId();

        // 작성자만 떼어낸다. Task 2의 탈퇴가 만들 상태를 미리 만든다.
        entityManager.createQuery("UPDATE Message m SET m.member = null WHERE m.id = :id")
                .setParameter("id", anonymousMessageId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    void 작성자가_없는_메시지도_목록에_포함된다() {
        MessageService.MessagePage page = messageService.getMessages(roomId, reader.getId(), null, 30);

        assertThat(page.messages()).extracting(Message::getId).contains(anonymousMessageId);
    }

    @Test
    void 작성자가_없는_메시지의_응답은_작성자_필드가_비어_있다() {
        Message message = messageRepository.findById(anonymousMessageId).orElseThrow();

        MessageResponse response = MessageResponse.from(message);

        assertThat(response.getMemberId()).isNull();
        assertThat(response.getNickname()).isNull();
        assertThat(response.getProfileImageUrl()).isNull();
        assertThat(response.getContent()).isEqualTo("남는 메시지");
    }

    @Test
    void 작성자가_없는_메시지는_수정할_수_없다() {
        assertThatThrownBy(() -> messageService.update(roomId, anonymousMessageId, reader.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_MESSAGE_OWNER);
    }

    @Test
    void 작성자가_없는_메시지는_삭제할_수_없다() {
        assertThatThrownBy(() -> messageService.delete(roomId, anonymousMessageId, reader.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_MESSAGE_OWNER);
    }
}
```

> `MessageService.MessagePage`는 `record MessagePage(List<Message> messages, boolean hasMore)`이고 `getMessages(chatroomId, memberId, before, limit)` 시그니처다. `MessageResponse`는 `@Getter`라 `getMemberId()`·`getNickname()`·`getProfileImageUrl()`·`getContent()`를 쓴다.

- [ ] **Step 3: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*AnonymousAuthorTest*'
```

기대: 4건 모두 FAIL. 목록 조회는 `JOIN FETCH`가 작성자 없는 행을 걸러 비어 있고, DTO와 수정·삭제는 `getMember()`가 `null`이라 `NullPointerException`이 난다.

- [ ] **Step 4: 조회를 LEFT JOIN FETCH로 바꾼다**

`MessageRepository.java`의 두 쿼리를 아래로 바꾼다.

```java
    // 최신 → 과거(id DESC). member는 fetch join으로 페이지 내 N+1 제거.
    // 작성자가 없는 메시지(탈퇴자)도 목록에 남아야 하므로 LEFT JOIN이다.
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.member WHERE m.chatRoom = :room ORDER BY m.id DESC")
    List<Message> findLatestByChatRoom(@Param("room") ChatRoom room, Pageable pageable);

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.member WHERE m.chatRoom = :room AND m.id < :before ORDER BY m.id DESC")
    List<Message> findOlderByChatRoom(@Param("room") ChatRoom room, @Param("before") Long before, Pageable pageable);
```

- [ ] **Step 5: DTO가 작성자 없음을 표현하게 한다**

`MessageResponse.from`을 아래로 바꾼다.

```java
    public static MessageResponse from(Message message){
        Member author = message.getMember();   // 탈퇴한 회원의 메시지는 작성자가 없다
        return new MessageResponse(
                message.getId(),
                message.getContent(),
                message.getImageUrl(),
                author != null ? author.getId() : null,
                author != null ? author.getNickname() : null,
                author != null ? author.getProfileImageUrl() : null,
                message.getChatRoom().getId(),
                message.getCreatedAt(),
                message.getReplyTo() != null ? message.getReplyTo().getId() : null,
                message.getEditedAt(),
                message.isDeleted()
        );
    }
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.domain.Member;
```

- [ ] **Step 6: 소유권 검사에 널 가드를 넣는다**

`MessageService`의 `update`와 `delete`에서 각각 아래 줄을

```java
        if (!message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
```

이렇게 바꾼다. **두 메서드 모두 바꾼다.**

```java
        // 작성자가 없는 메시지(탈퇴자)는 아무도 수정·삭제할 수 없다.
        if (message.getMember() == null || !message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests '*AnonymousAuthorTest*'
```

기대: PASS — 4 tests

- [ ] **Step 8: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java src/main/java/com/example/springboot_realtimechat/dto/MessageResponse.java src/main/java/com/example/springboot_realtimechat/service/MessageService.java src/test/java/com/example/springboot_realtimechat/message/AnonymousAuthorTest.java
git commit -m "feat(message): 작성자 없는 메시지를 조회와 응답에서 다루도록 함"
```

---

### Task 2: 탈퇴가 메시지를 익명화한다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/s3/MemberDeleteImageCleanupTest.java`
- Test: `src/test/java/com/example/springboot_realtimechat/member/AccountDeletionTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 널 처리 경로
- Produces: `MessageRepository#anonymizeByMember(Member member): int` — 작성자를 비운 행 수

**배경:** 지금 `MemberService.delete`는 메시지를 하드 삭제한다. 답장을 받은 적 있는 회원은 `fk_messages_reply`(RESTRICT)에 걸려 탈퇴가 500으로 실패한다.

**이 태스크의 검증은 반드시 커밋되는 테스트여야 한다.** 기존 탈퇴 테스트가 모두 `@Transactional`이라 롤백되고 DELETE SQL이 flush되지 않아, 이 결함이 CI를 통과해 왔다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/member/AccountDeletionTest.java`:

```java
package com.example.springboot_realtimechat.member;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 커밋되는 테스트다. @Transactional을 붙이면 DELETE/UPDATE가 flush되지 않아
// 외래키 제약이 실제로 평가되지 않는다.
@SpringBootTest
class AccountDeletionTest {

    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;

    @MockitoBean S3Service s3Service;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 답장을_받은_회원도_탈퇴할_수_있다() {
        Member author = memberService.create("del-author@e.com", "1234", "작성자");
        Member replier = memberService.create("del-replier@e.com", "1234", "답장자");
        ChatRoom room = chatRoomService.create("답장방");
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(replier.getId(), room.getId());

        Message original = messageService.create("원본", null, author.getId(), room.getId(), null);
        messageService.create("답장", null, replier.getId(), room.getId(), original.getId());

        assertThatCode(() -> memberService.delete(author.getId())).doesNotThrowAnyException();

        assertThat(memberRepository.findById(author.getId())).isEmpty();
    }

    @Test
    void 탈퇴해도_메시지는_남고_작성자만_비워진다() {
        Member author = memberService.create("del2-author@e.com", "1234", "작성자2");
        ChatRoom room = chatRoomService.create("보존방");
        chatRoomMemberService.join(author.getId(), room.getId());
        Message message = messageService.create("남을 내용", null, author.getId(), room.getId(), null);

        memberService.delete(author.getId());

        Message kept = messageRepository.findById(message.getId()).orElseThrow();
        assertThat(kept.getContent()).isEqualTo("남을 내용");
        assertThat(kept.getMember()).isNull();
    }

    @Test
    void 답장이_가리키던_원본이_그대로_남는다() {
        Member author = memberService.create("del3-author@e.com", "1234", "작성자3");
        Member replier = memberService.create("del3-replier@e.com", "1234", "답장자3");
        ChatRoom room = chatRoomService.create("링크방");
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(replier.getId(), room.getId());
        Message original = messageService.create("원본", null, author.getId(), room.getId(), null);
        Message reply = messageService.create("답장", null, replier.getId(), room.getId(), original.getId());

        memberService.delete(author.getId());

        Message keptReply = messageRepository.findById(reply.getId()).orElseThrow();
        assertThat(keptReply.getReplyTo()).isNotNull();
        assertThat(keptReply.getReplyTo().getId()).isEqualTo(original.getId());
    }
}
```

> `ChatRoomRepository`의 실제 이름이 다르면 `src/main/java/com/example/springboot_realtimechat/repository/`에서 확인해 맞춘다. `@AfterEach` 정리는 자식 테이블부터 지운다 — 순서를 바꾸면 외래키에 걸린다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*AccountDeletionTest*'
```

기대: `답장을_받은_회원도_탈퇴할_수_있다`가 FAIL — 외래키 위반(`DataIntegrityViolationException`). 나머지 둘도 메시지가 지워져 FAIL.

이 실패 출력을 보고서에 남긴다. 결함이 실재한다는 증거다.

- [ ] **Step 3: 익명화 쿼리 추가**

`MessageRepository.java`에서 `void deleteByMember(Member member);`를 지우고 아래를 추가한다.

```java
    // 탈퇴해도 대화는 남긴다. 작성자 참조만 끊는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Message m SET m.member = null WHERE m.member = :member")
    int anonymizeByMember(@Param("member") Member member);
```

import를 추가한다.

```java
import org.springframework.data.jpa.repository.Modifying;
```

- [ ] **Step 4: 탈퇴가 익명화하게 한다**

`MemberService.delete`를 아래로 바꾼다.

```java
    @Transactional
    public void delete(Long id){
        Member member = memberRepository.findById(id)
                .orElseThrow(()-> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 메시지는 남으므로 그 이미지들은 계속 참조된다. 정리 대상은 프로필 사진뿐이다.
        String profileImageUrl = member.getProfileImageUrl();

        chatRoomMemberRepository.deleteByMember(member);
        messageRepository.anonymizeByMember(member);
        memberRepository.delete(member);

        // 보안 동작(구독 회수)을 이미지 정리보다 먼저 실행한다.
        eventPublisher.publishEvent(new MemberDeletedEvent(id));
        if (profileImageUrl != null && !profileImageUrl.isBlank()) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(profileImageUrl));
        }
    }
```

이제 쓰이지 않는 import를 지운다.

```java
import java.util.LinkedHashSet;
import java.util.Set;
```

- [ ] **Step 5: 기존 탈퇴 테스트를 새 정책에 맞춘다**

`src/test/java/com/example/springboot_realtimechat/s3/MemberDeleteImageCleanupTest.java`의 두 테스트가 "메시지 이미지 URL도 발행된다"를 기대한다. 새 정책에서는 메시지가 남으므로 프로필만 발행된다.

`탈퇴하면_프로필과_이미지_메시지_URL마다_이벤트가_발행된다`를 아래로 바꾼다(이름도 함께 바꾼다).

```java
    @Test
    void 탈퇴하면_프로필_URL로만_이벤트가_발행된다() {
        Member member = memberService.create("del1@e.com", "1234", "탈퇴자");
        memberService.updateProfileImage(member.getId(), PROFILE);
        ChatRoom room = chatRoomService.create("탈퇴방");
        chatRoomMemberService.join(member.getId(), room.getId());
        messageService.create(null, IMAGE_A, member.getId(), room.getId(), null);
        messageService.create(null, IMAGE_B, member.getId(), room.getId(), null);

        memberService.delete(member.getId());

        assertThat(publishedUrls()).containsExactly(PROFILE);
    }
```

`같은_URL을_여러_메시지가_쓰면_한_번만_발행된다`는 전제가 사라졌으므로 지운다 — 메시지 이미지는 이제 발행 대상이 아니다.

`이미지가_없는_회원의_탈퇴는_이벤트를_발행하지_않는다`와 `구독_회수_이벤트가_이미지_정리보다_먼저_발행된다`는 그대로 둔다.

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests '*AccountDeletionTest*' --tests '*MemberDeleteImageCleanupTest*'
```

기대: 둘 다 PASS

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. 다른 테스트가 `deleteByMember`를 쓰고 있으면 `anonymizeByMember`로 바꾸되, 그 테스트가 검증하던 것을 약화시키지 않는다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java src/main/java/com/example/springboot_realtimechat/service/MemberService.java src/test/java/com/example/springboot_realtimechat/member/AccountDeletionTest.java src/test/java/com/example/springboot_realtimechat/s3/MemberDeleteImageCleanupTest.java
git commit -m "fix(member): 탈퇴 시 메시지를 지우지 않고 작성자만 비움"
```

---

### Task 3: 프론트가 작성자 없는 메시지를 표시한다

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/components/ChatArea.tsx`

**Interfaces:**
- Consumes: `MessageResponse`의 `memberId`·`nickname`·`profileImageUrl`이 `null`일 수 있다(Task 1)
- Produces: 없음

**배경:** `toMessage`가 `String(message.memberId)`와 `avatarForId(message.memberId)`를 그대로 쓴다. `null`이 오면 `"null"` 문자열 id와 잘못된 아바타가 만들어지고, 그 id로 프로필 모달을 열면 없는 회원을 조회해 오류가 뜬다.

- [ ] **Step 1: 현재 코드 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && sed -n '268,285p' frontend/src/lib/api.ts
```

`userId: String(message.memberId)`, `userName: message.nickname`, `userAvatar: avatarForId(message.memberId)`를 확인한다.

- [ ] **Step 2: 백엔드 응답 타입에 작성자 없음을 반영**

`frontend/src/lib/api.ts`의 `BackendMessage`에서 작성자 필드를 nullable로 바꾼다. 기존 정의에서 세 필드만 고친다.

```ts
  memberId: number | null;
  nickname: string | null;
  profileImageUrl?: string | null;
```

- [ ] **Step 3: 변환에서 작성자 없음을 다룬다**

`toMessage`를 아래로 바꾼다.

```ts
export function toMessage(message: BackendMessage): Message {
  // 탈퇴한 회원의 메시지는 작성자가 없다. 내용과 대화 구조는 그대로 남는다.
  const memberId = message.memberId;
  return {
    id: String(message.messageId),
    channelId: String(message.chatroomId),
    text: message.content,
    userId: memberId == null ? '' : String(memberId),
    userName: memberId == null ? '삭제된 사용자' : (message.nickname ?? ''),
    userAvatar: memberId == null ? '' : avatarForId(memberId),
    userPhotoUrl: message.profileImageUrl ?? undefined,
    createdAt: message.createdAt ? Date.parse(message.createdAt) : Date.now(),
    replyToId: message.replyToId != null ? String(message.replyToId) : undefined,
    imageUrl: message.imageUrl ?? undefined,
    edited: message.editedAt != null,
    deleted: message.deleted ?? false,
  };
}
```

지역변수로 받아 `memberId == null`로 판정하면 `avatarForId(memberId)` 자리에서 타입이 `number`로 좁혀진다. `avatarForId`의 인자 타입은 `string | number`라 `null`을 받지 않는다.

- [ ] **Step 4: 익명 작성자는 프로필을 열지 않는다**

`frontend/src/components/ChatArea.tsx`에서 아바타와 이름에 걸린 `onOpenProfile(msg.userId)` 두 곳을, `msg.userId`가 빈 문자열이면 열지 않도록 바꾼다.

```tsx
              <button
                onClick={() => { if (msg.userId) onOpenProfile(msg.userId); }}
                disabled={!msg.userId}
                className="cursor-pointer self-start flex-shrink-0 disabled:cursor-default"
                aria-label={`${msg.userName} 프로필`}
              >
```

이름 쪽도 같은 방식으로 바꾼다.

```tsx
                  <button
                    onClick={() => { if (msg.userId) onOpenProfile(msg.userId); }}
                    disabled={!msg.userId}
                    className="font-bold text-text cursor-pointer hover:text-accent-text transition-colors disabled:cursor-default disabled:hover:text-text"
                    aria-label={`${msg.userName} 프로필`}
                  >
```

- [ ] **Step 5: 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/lib/api.ts frontend/src/components/ChatArea.tsx
git commit -m "feat(frontend): 작성자 없는 메시지를 삭제된 사용자로 표시"
```

---

### Task 4: 최종 검증과 PR

**Files:** 없음 (검증만)

- [ ] **Step 1: 백엔드 전체 테스트**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test --rerun-tasks
```

기대: BUILD SUCCESSFUL. `--rerun-tasks`를 쓰는 이유는 `UP-TO-DATE` 캐시가 실행을 건너뛰면 검증이 아니기 때문이다.

- [ ] **Step 2: 프론트 전체 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0

- [ ] **Step 3: 스키마 변경이 없는지 확인**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git diff develop --stat -- 'src/main/resources/db/migration'
```

기대: 출력 없음

- [ ] **Step 4: 하드 삭제 경로가 남아 있지 않은지 확인**

```bash
grep -rn "deleteByMember" src/main/java src/test/java
```

기대: `chatRoomMemberRepository.deleteByMember`(멤버십 제거)만 남는다. `messageRepository.deleteByMember` 호출이나 선언이 남아 있으면 Task 2로 돌아간다.

- [ ] **Step 5: 설계 §7 자동 테스트 목록과 대조**

| §7 항목 | 테스트 |
|---|---|
| 답장을 받은 회원도 탈퇴할 수 있다(커밋되는 테스트) | `AccountDeletionTest.답장을_받은_회원도_탈퇴할_수_있다` |
| 메시지가 남고 작성자가 null | `AccountDeletionTest.탈퇴해도_메시지는_남고_작성자만_비워진다` |
| 답장이 원본을 계속 가리킨다 | `AccountDeletionTest.답장이_가리키던_원본이_그대로_남는다` |
| 작성자 없는 메시지가 목록에 포함된다 | `AnonymousAuthorTest.작성자가_없는_메시지도_목록에_포함된다` |
| 응답의 작성자 필드가 비어 있다 | `AnonymousAuthorTest.작성자가_없는_메시지의_응답은_작성자_필드가_비어_있다` |
| 수정·삭제가 `NOT_MESSAGE_OWNER`로 거부된다 | `AnonymousAuthorTest.작성자가_없는_메시지는_수정할_수_없다` / `..._삭제할_수_없다` |
| 탈퇴 시 프로필 이미지만 발행된다 | `MemberDeleteImageCleanupTest.탈퇴하면_프로필_URL로만_이벤트가_발행된다` |
| 구독 회수가 이미지 정리보다 먼저 | `MemberDeleteImageCleanupTest.구독_회수_이벤트가_이미지_정리보다_먼저_발행된다` |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 6: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓴다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** `AccountDeletionTest`에 `@Transactional`을 붙이면 롤백되어 외래키 제약이 평가되지 않는다 — 이 결함이 지금까지 CI를 통과한 이유가 그것이다.

```bash
git push -u origin feat/account-deletion-anonymize
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 7: 배포 후 실측 항목을 PR에 남긴다**

- 답장을 주고받은 계정으로 탈퇴가 성공하는지
- 탈퇴자의 메시지가 대화에 남고 "삭제된 사용자"로 보이는지
- 그 메시지를 인용한 답장이 원본을 계속 가리키는지
- 익명 작성자의 아바타·이름을 눌러도 프로필 모달이 열리지 않는지
- 탈퇴자의 프로필 사진에 orphan 태그가 붙는지(다른 곳에서 안 쓰는 경우)

---

## Self-Review

**스펙 커버리지 (설계 §2·§3·§7):**

| 요구 | 태스크 |
|---|---|
| D1 메시지 보존·작성자 참조 제거 | Task 2 |
| D2 마이그레이션 없음 | Global Constraints, Task 4 Step 3에서 확인 |
| D3 `LEFT JOIN FETCH` | Task 1 |
| D4 서버는 null, 문구는 프론트 | Task 1(백엔드), Task 3(프론트) |
| D5 익명 메시지는 수정·삭제 불가 | Task 1 |
| D6 정리 대상은 프로필뿐, 이벤트 순서 유지 | Task 2 |
| §7 커밋되는 탈퇴 테스트 | Task 2 |
| §7 나머지 자동 테스트 | Task 1·2, Task 4 Step 5에서 대조 |
| §7 프론트 검증 | Task 3 Step 5, Task 4 Step 2 |
