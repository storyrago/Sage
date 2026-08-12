# 답장 알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 내 메시지에 답장이 달린 방을, 랜딩 화면에서 일반 안읽음과 구분해 보여준다.

**Architecture:** "나에게 온 답장"을 저장하지 않고 파생 값으로 계산한다. 기존 안읽음 집계 쿼리에 조인 하나와 `COUNT` 하나를 얹어 방별 `replyCount`를 함께 반환하고, 실시간 개인 큐 이벤트(`/user/queue/unread`)에 `replyToMe` 플래그를 실어 배지를 즉시 갱신한다. 읽음 처리는 기존 `last_read_message_id`를 그대로 쓴다.

**Tech Stack:** Spring Boot 3 / Spring Data JPA (JPQL) / STOMP over WebSocket / Redis pub-sub / React + TypeScript (Vite)

설계 문서: `docs/superpowers/specs/2026-08-12-reply-notification-design.md`

## Global Constraints

- 스키마 변경 금지. Flyway 마이그레이션을 추가하지 않는다.
- 새 REST 엔드포인트를 만들지 않는다. `GET /api/chatrooms/unread`가 `replyCount`를 함께 싣는다.
- 새 테이블·새 읽음 상태를 만들지 않는다.
- `MessageResponse`를 건드리지 않는다(설계 함정 3).
- JPQL에서 암묵 조인(`m.replyTo.member`)을 쓰지 않는다. 명시 `LEFT JOIN`만 쓴다(설계 함정 1).
- 집계에 `SUM`을 쓰지 않는다. `COUNT`만 쓴다(설계 함정 2).
- 백엔드 검증: `./gradlew test`
- 프론트 검증: `cd frontend && npm run lint && npm run build`
- 커밋 메시지·주석에 배경 서사("누락됐다", "그래서 깨져 있었다")를 쓰지 않는다. 변경의 목적만 쓴다.

## File Structure

| 파일 | 역할 | 변경 |
|---|---|---|
| `src/main/java/.../repository/ChatRoomMemberRepository.java` | 방별 안읽음·답장 집계 | 수정 |
| `src/main/java/.../repository/UnreadCountProjection.java` | 집계 결과 프로젝션 | 수정 |
| `src/main/java/.../dto/UnreadCountResponse.java` | `GET /api/chatrooms/unread` 응답 | 수정 |
| `src/main/java/.../service/ChatRoomMemberService.java` | 프로젝션 → 응답 매핑 | 수정 |
| `src/main/java/.../repository/MessageRepository.java` | 부모 메시지 작성자 조회 | 수정 |
| `src/main/java/.../dto/UnreadEvent.java` | 개인 큐 이벤트 페이로드 | 수정 |
| `src/main/java/.../redis/RedisSubscriber.java` | 답장 여부 판정 후 fan-out | 수정 |
| `src/test/java/.../service/ReplyCountTest.java` | 집계 정확도·경계 조건 | 신규 |
| `src/test/java/.../redis/RedisSubscriberReplyTest.java` | 이벤트 판정 | 신규 |
| `frontend/src/lib/api.ts` | `UnreadCount` 타입 | 수정 |
| `frontend/src/lib/stomp.ts` | `onUnread` 페이로드 타입 | 수정 |
| `frontend/src/App.tsx` | 안읽음 상태를 `{count, replies}`로 통합 | 수정 |
| `frontend/src/components/ChannelLanding.tsx` | 우표 답장 표식 | 수정 |

---

### Task 1: 서버 집계 — 방별 `replyCount`

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java:24-38`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/UnreadCountProjection.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/dto/UnreadCountResponse.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java:170-174`
- Test: `src/test/java/com/example/springboot_realtimechat/service/ReplyCountTest.java`

**Interfaces:**
- Consumes: 기존 `MessageService.create(content, imageUrl, memberId, chatroomId, replyToId)`, `ChatRoomMemberService.getUnreadCounts(memberId)`, `ChatRoomMemberService.markRead(memberId, chatRoomId)`
- Produces: `UnreadCountProjection.getReplyCount() : long`, `UnreadCountResponse.getReplyCount() : long` — Task 3의 프론트 타입이 이 이름(`replyCount`)에 의존한다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`src/test/java/com/example/springboot_realtimechat/service/ReplyCountTest.java` 신규 생성:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.UnreadCountResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ReplyCountTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private UnreadCountResponse countsFor(Long memberId, Long roomId) {
        List<UnreadCountResponse> counts = chatRoomMemberService.getUnreadCounts(memberId);
        return counts.stream()
                .filter(c -> c.getChatroomId().equals(roomId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 내메시지에_달린_남의_답장만_센다() {
        Member a = memberService.create("rc-a@e.com", "1234", "a");
        Member b = memberService.create("rc-b@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장1", null, b.getId(), room.getId(), mine.getId());
        messageService.create("답장2", null, b.getId(), room.getId(), mine.getId());
        messageService.create("답장 아님", null, b.getId(), room.getId(), null);

        UnreadCountResponse a측 = countsFor(a.getId(), room.getId());
        assertThat(a측.getReplyCount()).isEqualTo(2L);
        assertThat(a측.getUnreadCount()).isEqualTo(3L);   // b가 보낸 3개 전부
    }

    @Test
    void 남의_메시지에_달린_답장은_안_센다() {
        Member a = memberService.create("rc-c@e.com", "1234", "a");
        Member b = memberService.create("rc-d@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message bMessage = messageService.create("b 메시지", null, b.getId(), room.getId(), null);
        messageService.create("b가 자기 글에 답장", null, b.getId(), room.getId(), bMessage.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isZero();
    }

    @Test
    void 내가_보낸_답장은_안_센다() {
        Member a = memberService.create("rc-e@e.com", "1234", "a");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("내가 내 글에 답장", null, a.getId(), room.getId(), mine.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isZero();
    }

    @Test
    void 삭제된_답장은_안_센다() {
        Member a = memberService.create("rc-f@e.com", "1234", "a");
        Member b = memberService.create("rc-g@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        Message reply = messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        messageService.delete(room.getId(), reply.getId(), b.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isZero();
    }

    @Test
    void 부모메시지가_삭제돼도_답장은_센다() {
        Member a = memberService.create("rc-h@e.com", "1234", "a");
        Member b = memberService.create("rc-i@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        messageService.delete(room.getId(), mine.getId(), a.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isEqualTo(1L);
    }

    @Test
    void 읽으면_답장_카운트도_0이_된다() {
        Member a = memberService.create("rc-j@e.com", "1234", "a");
        Member b = memberService.create("rc-k@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        chatRoomMemberService.markRead(a.getId(), room.getId());

        UnreadCountResponse after = countsFor(a.getId(), room.getId());
        assertThat(after.getReplyCount()).isZero();
        assertThat(after.getUnreadCount()).isZero();
    }

    // 설계 함정 1 회귀: 답장 조인이 INNER로 떨어지면 이 방의 unreadCount가 0으로 뭉개진다.
    @Test
    void 답장이_없는_방의_안읽음이_뭉개지지_않는다() {
        Member a = memberService.create("rc-l@e.com", "1234", "a");
        Member b = memberService.create("rc-m@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        messageService.create("m1", null, b.getId(), room.getId(), null);
        messageService.create("m2", null, b.getId(), room.getId(), null);

        UnreadCountResponse a측 = countsFor(a.getId(), room.getId());
        assertThat(a측.getUnreadCount()).isEqualTo(2L);
        assertThat(a측.getReplyCount()).isZero();
    }

    @Test
    void replyCount는_unreadCount를_넘지_않는다() {
        Member a = memberService.create("rc-n@e.com", "1234", "a");
        Member b = memberService.create("rc-o@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        messageService.create("일반", null, b.getId(), room.getId(), null);

        for (UnreadCountResponse c : chatRoomMemberService.getUnreadCounts(a.getId())) {
            assertThat(c.getReplyCount()).isLessThanOrEqualTo(c.getUnreadCount());
        }
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

```bash
./gradlew test --tests '*ReplyCountTest*'
```

Expected: 컴파일 실패. `UnreadCountResponse`에 `getReplyCount()`가 없다는 오류.

- [ ] **Step 3: 프로젝션에 `replyCount`를 추가한다**

`UnreadCountProjection.java` 전체를 다음으로 바꾼다:

```java
package com.example.springboot_realtimechat.repository;

public interface UnreadCountProjection {
    Long getChatroomId();
    Long getLastReadMessageId();
    long getUnreadCount();
    long getReplyCount();
}
```

- [ ] **Step 4: 집계 쿼리에 조인과 `COUNT`를 얹는다**

`ChatRoomMemberRepository.java`의 `findUnreadCountsByMemberId` 위 `@Query`를 다음으로 바꾼다:

```java
    @Query("""
        SELECT cm.chatRoom.id AS chatroomId,
               cm.lastReadMessageId AS lastReadMessageId,
               COUNT(m) AS unreadCount,
               COUNT(p) AS replyCount
        FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        LEFT JOIN Message m
            ON m.chatRoom = cm.chatRoom
           AND (m.member IS NULL OR m.member <> cm.member)
           AND m.deleted = false
           AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
        LEFT JOIN Message p
            ON p.id = m.replyTo.id
           AND p.member = cm.member
        WHERE cm.member.id = :memberId
          AND r.deletedAt IS NULL
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);
```

`p`는 PK로 매칭되어 최대 1행이므로 `COUNT(m)`을 부풀리지 않는다.
`m.replyTo.id`는 FK 컬럼을 직접 읽으므로 추가 조인을 만들지 않는다.

- [ ] **Step 5: 응답 DTO에 `replyCount`를 추가한다**

`UnreadCountResponse.java` 전체를 다음으로 바꾼다:

```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class UnreadCountResponse {
    private final Long chatroomId;
    private final long unreadCount;
    private final long replyCount;
    private final Long lastReadMessageId;

    public UnreadCountResponse(Long chatroomId, long unreadCount, long replyCount, Long lastReadMessageId) {
        this.chatroomId = chatroomId;
        this.unreadCount = unreadCount;
        this.replyCount = replyCount;
        this.lastReadMessageId = lastReadMessageId;
    }
}
```

- [ ] **Step 6: 서비스 매핑을 고친다**

`ChatRoomMemberService.java:170-174`의 `getUnreadCounts`를 다음으로 바꾼다:

```java
    public List<UnreadCountResponse> getUnreadCounts(Long memberId) {
        return chatRoomMemberRepository.findUnreadCountsByMemberId(memberId).stream()
                .map(p -> new UnreadCountResponse(
                        p.getChatroomId(), p.getUnreadCount(), p.getReplyCount(), p.getLastReadMessageId()))
                .toList();
    }
}
```

`UnreadCountResponse` 생성자를 쓰는 다른 지점이 있는지 확인하고 함께 고친다:

```bash
grep -rn "new UnreadCountResponse" src/main src/test
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

```bash
./gradlew test --tests '*ReplyCountTest*'
```

Expected: 8개 테스트 전부 PASS.

- [ ] **Step 8: 기존 안읽음 테스트가 깨지지 않았는지 확인한다**

```bash
./gradlew test --tests '*UnreadCountTest*'
```

Expected: 전부 PASS. 실패하면 함정 1(조인이 INNER로 떨어짐)을 의심한다.

- [ ] **Step 9: 커밋한다**

```bash
git add src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java \
        src/main/java/com/example/springboot_realtimechat/repository/UnreadCountProjection.java \
        src/main/java/com/example/springboot_realtimechat/dto/UnreadCountResponse.java \
        src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java \
        src/test/java/com/example/springboot_realtimechat/service/ReplyCountTest.java
git commit -m "feat(unread): 방별 안읽음에 나에게 온 답장 개수를 함께 집계한다"
```

---

### Task 2: 실시간 이벤트 — `replyToMe`

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/dto/UnreadEvent.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/redis/RedisSubscriber.java:26-55`
- Test: `src/test/java/com/example/springboot_realtimechat/redis/RedisSubscriberReplyTest.java`

**Interfaces:**
- Consumes: 기존 `ChatRoomMemberRepository.findMembersByChatRoomId(roomId) : List<Member>`, `MessageResponse`(변경 없음)
- Produces: `UnreadEvent(Long chatroomId, Long messageId, boolean replyToMe)` — **와이어 필드명은 `replyToMe`**. Task 3의 프론트가 이 이름에 의존한다.
  `MessageRepository.findAuthorIdById(Long messageId) : Long` — 없는 메시지이거나 작성자가 탈퇴했으면 `null`. 기존 `findMaxIdByChatRoom`과 같은 nullable 스칼라 스타일이다.

⚠️ 필드 이름을 `isReplyToMe`로 짓지 않는다. Lombok이 만드는 `isXxx()` 게터에서 Jackson이 `is` 접두사를 떼기 때문에, 필드를 `isReplyToMe`로 두면 와이어 이름이 `replyToMe`가 되어 서버 필드명과 어긋난다. 타이핑 인디케이터(`typing`)에서 같은 함정을 겪었다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`src/test/java/com/example/springboot_realtimechat/redis/RedisSubscriberReplyTest.java` 신규 생성:

```java
package com.example.springboot_realtimechat.redis;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.UnreadEvent;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisSubscriberReplyTest {

    private SimpMessagingTemplate messagingTemplate;
    private ChatRoomMemberRepository chatRoomMemberRepository;
    private MessageRepository messageRepository;
    private RedisSubscriber subscriber;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        chatRoomMemberRepository = mock(ChatRoomMemberRepository.class);
        messageRepository = mock(MessageRepository.class);
        subscriber = new RedisSubscriber(
                messagingTemplate, new ObjectMapper(), chatRoomMemberRepository, messageRepository);
    }

    /** memberId만 알면 되므로 목으로 세운다. */
    private Member member(long id) {
        Member m = mock(Member.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    private Message redisMessage(String json) {
        Message m = mock(Message.class);
        when(m.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private String messageJson(long messageId, long senderId, Long replyToId) {
        return """
            {"messageId":%d,"content":"c","imageUrl":null,"memberId":%d,"nickname":"n",
             "profileImageUrl":null,"chatroomId":7,"createdAt":"2026-08-12T00:00:00",
             "replyToId":%s,"editedAt":null,"deleted":false}
            """.formatted(messageId, senderId, replyToId == null ? "null" : replyToId.toString());
    }

    @Test
    void 부모메시지_작성자에게만_replyToMe가_실린다() {
        // 방 멤버: 1(부모 작성자), 2(보낸 사람), 3(제3자)
        when(chatRoomMemberRepository.findMembersByChatRoomId(7L))
                .thenReturn(List.of(member(1L), member(2L), member(3L)));
        when(messageRepository.findAuthorIdById(50L)).thenReturn(1L);

        subscriber.onMessage(redisMessage(messageJson(51L, 2L, 50L)), null);

        ArgumentCaptor<UnreadEvent> captor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), captor.capture());
        assertThat(captor.getValue().isReplyToMe()).isTrue();

        ArgumentCaptor<UnreadEvent> other = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("3"), eq("/queue/unread"), other.capture());
        assertThat(other.getValue().isReplyToMe()).isFalse();

        // 보낸 사람에겐 통지하지 않는다
        verify(messagingTemplate, never()).convertAndSendToUser(eq("2"), eq("/queue/unread"), any(Object.class));
    }

    @Test
    void 답장이_아니면_부모조회를_하지_않는다() {
        when(chatRoomMemberRepository.findMembersByChatRoomId(7L))
                .thenReturn(List.of(member(1L), member(2L)));

        subscriber.onMessage(redisMessage(messageJson(51L, 2L, null)), null);

        verify(messageRepository, never()).findAuthorIdById(any());
        ArgumentCaptor<UnreadEvent> captor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), captor.capture());
        assertThat(captor.getValue().isReplyToMe()).isFalse();
    }

    @Test
    void 부모_작성자가_탈퇴했으면_아무도_replyToMe가_아니다() {
        when(chatRoomMemberRepository.findMembersByChatRoomId(7L))
                .thenReturn(List.of(member(1L), member(2L)));
        when(messageRepository.findAuthorIdById(50L)).thenReturn(null);

        subscriber.onMessage(redisMessage(messageJson(51L, 2L, 50L)), null);

        ArgumentCaptor<UnreadEvent> captor = ArgumentCaptor.forClass(UnreadEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/unread"), captor.capture());
        assertThat(captor.getValue().isReplyToMe()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

```bash
./gradlew test --tests '*RedisSubscriberReplyTest*'
```

Expected: 컴파일 실패. `RedisSubscriber` 생성자 인자 개수 불일치, `findAuthorIdById` 없음, `UnreadEvent` 생성자 인자 개수 불일치.

- [ ] **Step 3: 부모 작성자 조회를 추가한다**

`MessageRepository.java`에 다음 메서드를 추가한다(import 추가 없이 기존 것으로 충분하다):

```java
    /** 부모 메시지 작성자 판정용. FK 컬럼만 읽으므로 조인이 생기지 않는다. 탈퇴자·없는 메시지는 null. */
    @Query("SELECT m.member.id FROM Message m WHERE m.id = :messageId")
    Long findAuthorIdById(@Param("messageId") Long messageId);
```

- [ ] **Step 4: 이벤트에 `replyToMe`를 추가한다**

`UnreadEvent.java` 전체를 다음으로 바꾼다:

```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class UnreadEvent {
    private final Long chatroomId;
    private final Long messageId;
    // 필드명을 isReplyToMe로 두면 Jackson이 게터의 is 접두사를 떼어 와이어 이름이 어긋난다.
    private final boolean replyToMe;

    public UnreadEvent(Long chatroomId, Long messageId, boolean replyToMe) {
        this.chatroomId = chatroomId;
        this.messageId = messageId;
        this.replyToMe = replyToMe;
    }
}
```

- [ ] **Step 5: `RedisSubscriber`가 판정하게 한다**

`RedisSubscriber.java`에 `MessageRepository` 의존을 추가하고(`private final MessageRepository messageRepository;`, import 포함), 안읽음 fan-out 블록을 다음으로 바꾼다:

```java
        if (messageResponse.getEditedAt() == null && !messageResponse.isDeleted()) {
            try {
                // 답장이면 부모 작성자를 한 번만 조회한다. 방 멤버 순회 안에서 조회하면 멤버 수만큼 쿼리가 늘어난다.
                Long replyToAuthorId = messageResponse.getReplyToId() == null
                        ? null
                        : messageRepository.findAuthorIdById(messageResponse.getReplyToId());

                // 안읽음 fan-out: 방 멤버(보낸 사람 제외)에게 개인 큐로 통지. 각 서버가 자기 로컬 세션에 라우팅(멀티서버 안전).
                List<Member> members = chatRoomMemberRepository.findMembersByChatRoomId(messageResponse.getChatroomId());
                for (Member member : members) {
                    if (member.getId().equals(messageResponse.getMemberId())) continue; // 보낸 사람 제외
                    boolean replyToMe = replyToAuthorId != null && replyToAuthorId.equals(member.getId());
                    UnreadEvent event = new UnreadEvent(
                            messageResponse.getChatroomId(), messageResponse.getMessageId(), replyToMe);
                    try {
                        messagingTemplate.convertAndSendToUser(String.valueOf(member.getId()), "/queue/unread", event);
                    } catch (Exception e) {
                        log.warn("안읽음 전송 실패 (memberId={})", member.getId(), e);
                    }
                }
            } catch (Exception e) {
                log.error("안읽음 fan-out 실패 (chatroomId={})", messageResponse.getChatroomId(), e);
            }
        }
```

`UnreadEvent` 생성이 루프 밖에서 안으로 들어온다. 수신자마다 `replyToMe`가 다르기 때문이다.

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
./gradlew test --tests '*RedisSubscriberReplyTest*'
```

Expected: 3개 테스트 전부 PASS.

- [ ] **Step 7: 백엔드 전체 테스트를 돌린다**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. 실패가 있으면 `new UnreadEvent(` 호출 지점이 더 있는지 확인한다:

```bash
grep -rn "new UnreadEvent" src/main src/test
```

- [ ] **Step 8: 커밋한다**

```bash
git add src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java \
        src/main/java/com/example/springboot_realtimechat/dto/UnreadEvent.java \
        src/main/java/com/example/springboot_realtimechat/redis/RedisSubscriber.java \
        src/test/java/com/example/springboot_realtimechat/redis/RedisSubscriberReplyTest.java
git commit -m "feat(unread): 실시간 안읽음 통지에 나에게 온 답장인지 표시한다"
```

---

### Task 3: 프론트 상태 배선

**Files:**
- Modify: `frontend/src/lib/api.ts:201-205`
- Modify: `frontend/src/lib/stomp.ts:22`, `frontend/src/lib/stomp.ts:190-191`
- Modify: `frontend/src/App.tsx` (아래 6개 지점)
- Modify: `frontend/src/components/ChannelLanding.tsx:163`, `:624`

**Interfaces:**
- Consumes: Task 1의 `UnreadCount.replyCount`, Task 2의 `UnreadEvent.replyToMe`
- Produces: `unread` 상태 타입 `Record<string, { count: number; replies: number }>` — Task 4의 `ChannelLanding` 렌더가 이 형태에 의존한다.

⚠️ **안읽음 상태를 두 개(`unread`, `replyUnread`)로 나누지 않는다.** `setUnread` 호출 지점이 6곳이라, 하나라도 빠지면 방을 나가거나 강퇴당한 뒤에 답장 배지만 남는다. 값 하나에 두 숫자를 담아 구조적으로 어긋날 수 없게 만든다.

- [ ] **Step 1: API 타입에 `replyCount`를 추가한다**

`frontend/src/lib/api.ts:201-205`의 인터페이스를 다음으로 바꾼다:

```ts
export interface UnreadCount {
  chatroomId: number;
  unreadCount: number;
  replyCount: number;
  lastReadMessageId: number | null;
}
```

- [ ] **Step 2: STOMP 페이로드 타입에 `replyToMe`를 추가한다**

`frontend/src/lib/stomp.ts:22`를 다음으로 바꾼다:

```ts
  onUnread?: (evt: { chatroomId: number; messageId: number; replyToMe: boolean }) => void;
```

같은 파일 `:191`의 캐스팅도 맞춘다:

```ts
          this.options.onUnread?.(payload as { chatroomId: number; messageId: number; replyToMe: boolean });
```

- [ ] **Step 3: `App.tsx`의 안읽음 상태를 `{count, replies}`로 바꾼다**

`frontend/src/App.tsx:75`:

```tsx
  const [unread, setUnread] = useState<Record<string, { count: number; replies: number }>>({});
```

`:245` (최초 조회):

```tsx
          setUnread(Object.fromEntries(counts.map((c) => [String(c.chatroomId), { count: c.unreadCount, replies: c.replyCount }])));
```

`:318` (입장 시 읽음 처리):

```tsx
            setUnread((prev) => ({ ...prev, [selectedChannelId]: { count: 0, replies: 0 } }));
```

`:384` (재연결 후 보정):

```tsx
              setUnread(Object.fromEntries(counts.map((c) => [String(c.chatroomId), { count: c.unreadCount, replies: c.replyCount }])));
```

`:448-452` (실시간 수신):

```tsx
        onUnread: ({ chatroomId, replyToMe }) => {
          const roomId = String(chatroomId);
          if (roomId === selectedChannelRef.current) return; // 지금 보는 방은 무시
          setUnread((prev) => {
            const cur = prev[roomId] ?? { count: 0, replies: 0 };
            return { ...prev, [roomId]: { count: cur.count + 1, replies: cur.replies + (replyToMe ? 1 : 0) } };
          });
        },
```

`:469`와 `:757`의 키 삭제(`setUnread(({ [x]: _removed, ...rest }) => rest)`)는 **그대로 둔다.** 값 형태만 바뀌었을 뿐 키 단위 삭제는 동일하다.

- [ ] **Step 4: `ChannelLanding`이 새 형태를 받게 한다**

`frontend/src/components/ChannelLanding.tsx:163`:

```tsx
  unread?: Record<string, { count: number; replies: number }>;
```

같은 파일 `:624`:

```tsx
            const entry = unread?.[ch.id];
            const count = entry?.count ?? 0;
            const replies = entry?.replies ?? 0;
```

- [ ] **Step 5: 타입 검사와 빌드를 돌린다**

```bash
cd frontend && npm run lint && npm run build
```

Expected: exit 0. `replies`가 아직 안 쓰여 미사용 변수 경고가 나면 Task 4에서 쓰므로, 이 단계에서만 `void replies;` 같은 임시 코드를 넣지 말고 Task 4를 이어서 진행한다.

- [ ] **Step 6: 커밋한다**

```bash
git add frontend/src/lib/api.ts frontend/src/lib/stomp.ts frontend/src/App.tsx frontend/src/components/ChannelLanding.tsx
git commit -m "feat(frontend): 방별 안읽음 상태에 답장 개수를 함께 담는다"
```

---

### Task 4: 우표 답장 표식 + 실제 화면 확인

**Files:**
- Modify: `frontend/src/components/ChannelLanding.tsx:2` (아이콘 import), `:663-666` (배지 렌더)

**Interfaces:**
- Consumes: Task 3의 `replies` 지역 변수

설계에 따라 **모양은 실제 화면을 보고 정한다.** 아래는 출발점이고, 화면 확인 후 조정한다.
잠금 배지(`ch.locked && !ch.joined`)가 우표 우하단에 이미 있으므로, 답장 표식은 **우상단**에 같은 패턴으로 붙인다.

- [ ] **Step 1: 아이콘을 import한다**

`frontend/src/components/ChannelLanding.tsx:2`의 import에 `Reply`를 추가한다:

```tsx
import { Plus, Hash, Code, Music, Shuffle, Gamepad2, MessageCircle, Bell, X, LogOut, Lock, UserX, Crown, Reply } from 'lucide-react';
```

- [ ] **Step 2: 답장 표식을 렌더한다**

`ChannelLanding.tsx`의 소인(`{count > 0 && (...)}`) 블록 **바로 뒤**에 다음을 넣는다:

```tsx
                  {replies > 0 && (
                    <span
                      aria-label={`나에게 온 답장 ${replies}개`}
                      className="absolute right-1 top-1 md:right-1.5 md:top-1.5 w-4 h-4 md:w-5 md:h-5 rounded-full bg-[#C2402C] flex items-center justify-center pointer-events-none"
                    >
                      <Reply className="w-2.5 h-2.5 md:w-3 md:h-3 text-[#f5efe6]" strokeWidth={2.5} />
                    </span>
                  )}
```

- [ ] **Step 3: 타입 검사와 빌드를 돌린다**

```bash
cd frontend && npm run lint && npm run build
```

Expected: exit 0.

- [ ] **Step 4: 실제 화면을 띄운다**

백엔드와 프론트를 띄운다. 백엔드는 `JWT_SECRET`이 없으면 부팅하지 않는다.

```bash
JWT_SECRET=local-throwaway-secret-for-verification-only ./gradlew bootRun
```

프론트는 `.claude/launch.json`의 preview 설정으로 띄운다(Bash로 dev server를 직접 실행하지 않는다).

- [ ] **Step 5: 답장 상황을 만들어 눈으로 확인한다**

두 계정으로 확인한다. **브라우저 두 탭은 쓰지 않는다** — 같은 프로필이면 `localStorage`를 공유해 세션이 덮어써진다.
한쪽은 브라우저, 다른 쪽은 REST/STOMP 스크립트로 만든다.

확인 항목:
1. A가 보낸 메시지에 B가 답장 → A의 랜딩에서 그 방 우표에 **소인(안읽음)과 답장 표식이 함께** 보인다.
2. B가 일반 메시지만 보낸 방 → 소인만 있고 답장 표식은 없다.
3. A가 그 방에 입장했다 나오면 → 소인·답장 표식이 **둘 다** 사라진다.
4. **F5로 새로고침해도 1·3의 상태가 유지된다**(서버 집계에서 복원).
5. 모바일 폭(375px)에서 표식이 우표 밖으로 나가거나 채널명을 가리지 않는다.

⚠️ 이 환경의 브라우저 자동화는 rAF가 진행되지 않아 우표 확대(FLIP)·페이드인이 끝나지 않는다.
입장 버튼이 `pointer-events:none`으로 남으면 DOM에서 버튼을 찾아 `.click()`으로 우회한다.
스크린샷보다 **JS로 DOM 상태를 세는 방식**이 안정적이다.

- [ ] **Step 6: 화면을 보고 모양을 조정한다**

1~5에서 어색한 점(표식이 소인과 겹침, 너무 작아 안 보임, 색이 배경에 묻힘 등)을 고친다.
PR #76에서 목업만 믿고 진행했다가 실제 화면에서 3건을 고쳐야 했다. **반드시 실제 화면을 보고 판단한다.**

- [ ] **Step 7: 커밋한다**

```bash
git add frontend/src/components/ChannelLanding.tsx
git commit -m "feat(frontend): 랜딩 우표에 나에게 온 답장 표식을 표시한다"
```

---

### Task 5: 최종 검증과 PR

**Files:** 없음(검증·PR만)

- [ ] **Step 1: 백엔드 전체 테스트**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. 통과한 테스트 수를 기록한다(PR 본문의 `## 검증`에 쓴다).

- [ ] **Step 2: 프론트 검증**

```bash
cd frontend && npm run lint && npm run build
```

Expected: 둘 다 exit 0.

- [ ] **Step 3: 뮤테이션으로 테스트가 실제로 잠그는지 확인한다**

다음을 각각 되돌려 보고 **테스트가 빨간지** 확인한 뒤 원복한다. 초록이면 테스트가 그 자리를 안 지키는 것이다.

| 되돌릴 것 | 빨개져야 할 테스트 |
|---|---|
| `LEFT JOIN Message p` → `JOIN Message p` | `답장이_없는_방의_안읽음이_뭉개지지_않는다` |
| `ON ... AND p.member = cm.member` → `ON p.id = m.replyTo.id` | `남의_메시지에_달린_답장은_안_센다` |
| `replyToAuthorId.equals(member.getId())` → `true` | `부모메시지_작성자에게만_replyToMe가_실린다` |

- [ ] **Step 4: PR을 만든다**

`.github/pull_request_template.md`의 5개 섹션을 같은 순서·같은 제목으로 채운다.
`## 검증`에는 **실제로 실행한 것만** 쓴다. 실제 화면 확인을 안 했으면 안 했다고 적는다.
`## 배포 영향`에는 스키마 변경 없음·마이그레이션 없음·환경변수 없음을 명시한다.

```bash
git push -u origin feat/reply-notification
```

base는 `develop`이다. main을 타겟으로 하지 않는다.

---

## Self-Review

**스펙 커버리지**

| 스펙 항목 | 담당 태스크 |
|---|---|
| §1 서버 집계 `replyCount` | Task 1 |
| §1 함정 1(암묵 조인) | Task 1 Step 4 + 테스트 `답장이_없는_방의_안읽음이_뭉개지지_않는다` + Task 5 뮤테이션 |
| §1 함정 2(`SUM` null) | Task 1 Step 4에서 `COUNT`만 사용 |
| §1 불변식 `replyCount ≤ unreadCount` | Task 1 테스트 `replyCount는_unreadCount를_넘지_않는다` |
| §2 `replyToMe` 실시간 이벤트 | Task 2 |
| §2 함정 3(`MessageResponse` 미변경) | Task 2에서 리포지토리 조회로 처리, Global Constraints에 명시 |
| §3 프론트 배지·낙관적 갱신·읽음 시 0 | Task 3, Task 4 |
| §4 경계 조건 5개 | Task 1 테스트 6개 |
| §5 검증 | Task 5 |
| §6 배포 영향(스키마 무변경) | Global Constraints, Task 5 Step 4 |

**타입 일관성**

- `replyCount`(서버 `long` / 프론트 `number`) — Task 1 ↔ Task 3
- `replyToMe`(서버 `boolean` 필드명 / 프론트 `boolean`) — Task 2 ↔ Task 3
- `unread` 상태 `Record<string, { count: number; replies: number }>` — Task 3 ↔ Task 4
