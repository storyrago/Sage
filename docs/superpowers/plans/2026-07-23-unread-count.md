# 실시간 안읽음 카운트 + 안읽음부터 보기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 방 목록에 실시간 안읽음 카운트 배지를 띄우고, 안읽음 있는 방 입장 시 첫 안읽음 메시지로 스크롤 + "여기부터 안읽음" 구분선을 보여준다.

**Architecture:** `chatroom_members.last_read_message_id`(회원·방별 읽음 포인터)로 안읽음을 계산. 실시간은 메시지 생성 시 각 서버의 `RedisSubscriber`가 방 멤버(보낸사람 제외)에게 `convertAndSendToUser(email, "/queue/unread", …)`로 fan-out(멀티서버 안전). 프론트는 `/user/queue/unread` 상시 구독 + 방별 `unread` 상태로 배지, 입장 시 last_read 스냅샷으로 구분선.

**Tech Stack:** Spring Boot 4 / JPA(Hibernate 7) / MySQL / Flyway / STOMP(SimpleBroker + Redis pub/sub) / React + TS(Vite).

## Global Constraints

- DB 스키마 변경은 **Flyway 마이그레이션 파일**로만 (`ddl-auto: validate` 유지). 수동 ALTER 금지.
- STOMP Principal 이름 = **email** (`CustomUserDetails.getUsername()`). `convertAndSendToUser`의 user 인자는 대상 회원의 email.
- 백엔드 테스트: `@SpringBootTest`(H2, `MODE=MySQL`), `catchThrowableOfType(Class, callable)`(비-deprecated 순서).
- 프론트: 테스트 러너 없음 → 검증 = `npm run lint`(tsc) + `npm run build` + 격리 하니스(브라우저 실측). 커밋 전 lint·build exit 0 필수.
- 안읽음 정의: `messages` 중 `id > last_read AND member_id != 나 AND deleted = false`. `last_read`가 null이면 방 전체.
- 브랜치 `feat/unread-count`(develop 분기, Flyway 포함). 커밋 자주.

---

## Task 1: Flyway V2 — last_read_message_id 컬럼 + 엔티티 필드

**Files:**
- Create: `src/main/resources/db/migration/V2__add_chatroom_members_last_read.sql`
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/ChatRoomMember.java`

**Interfaces:**
- Produces: `ChatRoomMember.getLastReadMessageId(): Long`, `ChatRoomMember.updateLastRead(Long)`.

- [ ] **Step 1: 마이그레이션 파일 작성**

`V2__add_chatroom_members_last_read.sql`:
```sql
ALTER TABLE chatroom_members
  ADD COLUMN last_read_message_id BIGINT NULL;
```

- [ ] **Step 2: 엔티티에 필드 + 메서드 추가**

`ChatRoomMember.java` — `chatRoom` 필드 뒤에 추가:
```java
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;
```
클래스 마지막 `}` 앞에 추가:
```java
    public void updateLastRead(Long messageId) {
        this.lastReadMessageId = messageId;
    }
```
(`@Getter`가 이미 있어 `getLastReadMessageId()` 자동 생성.)

- [ ] **Step 3: 로컬 실제 MySQL로 마이그레이션 검증**

```bash
export PATH="/usr/local/mysql/bin:$PATH"
mysql -h 127.0.0.1 -uroot -p1111 -e "DROP DATABASE IF EXISTS uc_fresh; CREATE DATABASE uc_fresh;"
JWT_SECRET=x SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/uc_fresh' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=1111 \
./gradlew bootRun > /tmp/uc.log 2>&1 &
# 부팅 후:
grep -iE "Successfully applied|Started SpringbootRealtime|Schema validation" /tmp/uc.log
mysql -h 127.0.0.1 -uroot -p1111 -e "SHOW COLUMNS FROM chatroom_members LIKE 'last_read_message_id';" uc_fresh
pkill -f SpringbootRealtimechatApplication
```
Expected: `Successfully applied 2 migrations` (V1+V2), `Started …`(validate 통과), 컬럼 존재.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V2__add_chatroom_members_last_read.sql src/main/java/com/example/springboot_realtimechat/domain/ChatRoomMember.java
git commit -m "feat(unread): chatroom_members.last_read_message_id (Flyway V2)"
```

---

## Task 2: 가입 시 last_read = 방 최신 메시지 id

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/service/UnreadCountTest.java` (create)

**Interfaces:**
- Produces: `MessageRepository.findMaxIdByChatRoom(ChatRoom): Long` (없으면 null). `ChatRoomMemberService.join`이 가입 시 lastRead를 최신 id로 세팅.

- [ ] **Step 1: 실패 테스트 작성**

`UnreadCountTest.java`:
```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class UnreadCountTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;

    @Test
    void 가입시_lastRead가_방_최신메시지id로_세팅() {
        Member owner = memberService.create("o@e.com", "1234", "owner");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(owner.getId(), room.getId());
        messageService.create("m1", null, owner.getId(), room.getId(), null);
        var last = messageService.create("m2", null, owner.getId(), room.getId(), null);

        Member joiner = memberService.create("j@e.com", "1234", "joiner");
        ChatRoomMember cm = chatRoomMemberService.join(joiner.getId(), room.getId());

        assertThat(cm.getLastReadMessageId()).isEqualTo(last.getId());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*UnreadCountTest"`
Expected: FAIL (컴파일 에러 `getLastReadMessageId`는 있으나 join이 세팅 안 함 → null != last.id).

- [ ] **Step 3: 구현**

`MessageRepository.java`에 추가:
```java
    @Query("SELECT MAX(m.id) FROM Message m WHERE m.chatRoom = :room")
    Long findMaxIdByChatRoom(@Param("room") ChatRoom room);
```
`ChatRoomMemberService.java`: 필드에 `private final MessageRepository messageRepository;` 추가. `join`에서 `new ChatRoomMember(member, chatRoom)` 직후·`saveAndFlush` 전에:
```java
        chatRoomMember.updateLastRead(messageRepository.findMaxIdByChatRoom(chatRoom));
```
import: `com.example.springboot_realtimechat.repository.MessageRepository`.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*UnreadCountTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/.../repository/MessageRepository.java src/main/java/.../service/ChatRoomMemberService.java src/test/java/.../service/UnreadCountTest.java
git commit -m "feat(unread): 가입 시 last_read를 방 최신 메시지로 세팅"
```

---

## Task 3: 안읽음 카운트 조회 (쿼리 + 서비스 + DTO + 엔드포인트)

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java`
- Create: `src/main/java/com/example/springboot_realtimechat/repository/UnreadCountProjection.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/UnreadCountResponse.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java` (`GET /api/chatrooms/unread`)
- Test: `UnreadCountTest.java` (add)

**Interfaces:**
- Produces: `ChatRoomMemberService.getUnreadCounts(Long memberId): List<UnreadCountResponse>` where `UnreadCountResponse{ Long chatroomId; long unreadCount; Long lastReadMessageId; }`. Endpoint `GET /api/chatrooms/unread` → `List<UnreadCountResponse>`.

- [ ] **Step 1: 실패 테스트 추가**

`UnreadCountTest.java`에 추가:
```java
    @Test
    void 안읽음_카운트는_내메시지_삭제_제외하고_lastRead_이후만() {
        Member a = memberService.create("a@e.com", "1234", "a");
        Member b = memberService.create("b@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(a.getId(), room.getId());   // a: lastRead=null(빈 방)
        chatRoomMemberService.join(b.getId(), room.getId());

        // b가 5개 보냄. a 입장에서 5개 안읽음이어야(내것 아님, 삭제 아님)
        for (int i = 0; i < 5; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);
        // a가 1개 보냄 → a의 안읽음엔 안 셈(내 메시지)
        messageService.create("mine", null, a.getId(), room.getId(), null);
        // b의 1개 삭제 → 안읽음에서 빠짐
        var del = messageService.create("del", null, b.getId(), room.getId(), null);
        messageService.delete(del.getId(), b.getId());

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var forRoom = counts.stream().filter(c -> c.getChatroomId().equals(room.getId())).findFirst().orElseThrow();
        assertThat(forRoom.getUnreadCount()).isEqualTo(5L);  // b0~b4
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "*UnreadCountTest"` → FAIL (`getUnreadCounts` 없음).

- [ ] **Step 3: 구현**

`UnreadCountProjection.java`:
```java
package com.example.springboot_realtimechat.repository;

public interface UnreadCountProjection {
    Long getChatroomId();
    Long getLastReadMessageId();
    long getUnreadCount();
}
```
`ChatRoomMemberRepository.java`에 추가 (imports: `Query`, `Param`, `List`, `UnreadCountProjection`):
```java
    @Query("""
        SELECT cm.chatRoom.id AS chatroomId,
               cm.lastReadMessageId AS lastReadMessageId,
               COUNT(m) AS unreadCount
        FROM ChatRoomMember cm
        LEFT JOIN Message m
            ON m.chatRoom = cm.chatRoom
           AND m.member <> cm.member
           AND m.deleted = false
           AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
        WHERE cm.member.id = :memberId
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);
```
`UnreadCountResponse.java`:
```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class UnreadCountResponse {
    private final Long chatroomId;
    private final long unreadCount;
    private final Long lastReadMessageId;

    public UnreadCountResponse(Long chatroomId, long unreadCount, Long lastReadMessageId) {
        this.chatroomId = chatroomId;
        this.unreadCount = unreadCount;
        this.lastReadMessageId = lastReadMessageId;
    }
}
```
`ChatRoomMemberService.java`에 추가:
```java
    public List<UnreadCountResponse> getUnreadCounts(Long memberId) {
        return chatRoomMemberRepository.findUnreadCountsByMemberId(memberId).stream()
                .map(p -> new UnreadCountResponse(p.getChatroomId(), p.getUnreadCount(), p.getLastReadMessageId()))
                .toList();
    }
```
imports: `UnreadCountResponse`, `List`.
`ChatRoomController.java`: 필드 `private final ChatRoomMemberService chatRoomMemberService;` 추가, imports(`CustomUserDetails`, `AuthenticationPrincipal`, `UnreadCountResponse`, `ChatRoomMemberService`), 그리고:
```java
    @GetMapping("/unread")
    public List<UnreadCountResponse> getUnreadCounts(@AuthenticationPrincipal CustomUserDetails user) {
        return chatRoomMemberService.getUnreadCounts(user.getMemberId());
    }
```
주의: `@GetMapping("/unread")`는 `@GetMapping("/{id}")`보다 **먼저** 선언(경로 충돌 방지 — Spring은 리터럴 우선이라 안전하나 명시적으로 위에 둘 것).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "*UnreadCountTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/.../repository/ChatRoomMemberRepository.java src/main/java/.../repository/UnreadCountProjection.java src/main/java/.../dto/UnreadCountResponse.java src/main/java/.../service/ChatRoomMemberService.java src/main/java/.../controller/ChatRoomController.java src/test/java/.../service/UnreadCountTest.java
git commit -m "feat(unread): GET /api/chatrooms/unread (내메시지·삭제 제외 집계)"
```

---

## Task 4: 읽음 처리 (markRead + POST endpoint)

**Files:**
- Modify: `ChatRoomMemberService.java`
- Modify: `ChatRoomController.java` (`POST /api/chatrooms/{id}/read`)
- Test: `UnreadCountTest.java` (add)

**Interfaces:**
- Produces: `ChatRoomMemberService.markRead(Long memberId, Long chatRoomId)` — 그 방 last_read를 최신 메시지 id로 갱신. Endpoint `POST /api/chatrooms/{id}/read` → 204.

- [ ] **Step 1: 실패 테스트 추가**

```java
    @Test
    void 읽음처리하면_안읽음_0() {
        Member a = memberService.create("ra@e.com", "1234", "ra");
        Member b = memberService.create("rb@e.com", "1234", "rb");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(a.getId(), room.getId());
        chatRoomMemberService.join(b.getId(), room.getId());
        for (int i = 0; i < 3; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);

        chatRoomMemberService.markRead(a.getId(), room.getId());

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var forRoom = counts.stream().filter(c -> c.getChatroomId().equals(room.getId())).findFirst().orElseThrow();
        assertThat(forRoom.getUnreadCount()).isEqualTo(0L);
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests "*UnreadCountTest"` → FAIL (`markRead` 없음).

- [ ] **Step 3: 구현**

`ChatRoomMemberService.java`에 추가:
```java
    @Transactional
    public void markRead(Long memberId, Long chatRoomId) {
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        ChatRoomMember cm = chatRoomMemberRepository.findByMemberAndChatRoom(member, chatRoom)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_JOINED_ROOM));
        cm.updateLastRead(messageRepository.findMaxIdByChatRoom(chatRoom));
    }
```
imports: `CustomException`, `ErrorCode`, `Transactional`(이미 있으면 생략).
`ChatRoomController.java`에 추가:
```java
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomMemberService.markRead(user.getMemberId(), id);
    }
```
imports: `ResponseStatus`, `HttpStatus`.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests "*UnreadCountTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/.../service/ChatRoomMemberService.java src/main/java/.../controller/ChatRoomController.java src/test/java/.../service/UnreadCountTest.java
git commit -m "feat(unread): POST /api/chatrooms/{id}/read 읽음 처리"
```

---

## Task 5: 실시간 fan-out (RedisSubscriber → per-user unread 이벤트)

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/dto/UnreadEvent.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java` (방 멤버 조회)
- Modify: `src/main/java/com/example/springboot_realtimechat/redis/RedisSubscriber.java`
- Test: `UnreadCountTest.java` (멤버 조회 쿼리만 검증; STOMP 전송은 프론트 하니스에서 검증)

**Interfaces:**
- Produces: `ChatRoomMemberRepository.findMembersByChatRoomId(Long): List<Member>`(email 접근 가능하게 join으로 초기화). `UnreadEvent{ Long chatroomId; Long messageId; }`. RedisSubscriber가 메시지 방송 후 멤버(보낸사람 제외)에게 `/queue/unread` 전송.

- [ ] **Step 1: 멤버 조회 실패 테스트**

```java
    @Autowired ChatRoomMemberRepository repoForMembers;  // (이미 chatRoomMemberRepository 있으면 재사용)

    @Test
    void 방_멤버_조회_email_접근가능() {
        Member a = memberService.create("ma@e.com", "1234", "ma");
        Member b = memberService.create("mb@e.com", "1234", "mb");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(a.getId(), room.getId());
        chatRoomMemberService.join(b.getId(), room.getId());

        var members = chatRoomMemberRepository.findMembersByChatRoomId(room.getId());
        assertThat(members).extracting(Member::getEmail)
                .containsExactlyInAnyOrder("ma@e.com", "mb@e.com");
    }
```
(import `com.example.springboot_realtimechat.domain.Member`.)

- [ ] **Step 2: 실패 확인** — FAIL (`findMembersByChatRoomId` 없음).

- [ ] **Step 3: 구현**

`ChatRoomMemberRepository.java`에 추가:
```java
    @Query("SELECT m FROM ChatRoomMember cm JOIN cm.member m WHERE cm.chatRoom.id = :roomId")
    List<Member> findMembersByChatRoomId(@Param("roomId") Long roomId);
```
(import `Member`.)
`UnreadEvent.java`:
```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class UnreadEvent {
    private final Long chatroomId;
    private final Long messageId;

    public UnreadEvent(Long chatroomId, Long messageId) {
        this.chatroomId = chatroomId;
        this.messageId = messageId;
    }
}
```
`RedisSubscriber.java`: 필드 `private final ChatRoomMemberRepository chatRoomMemberRepository;` 추가(imports: repo, `UnreadEvent`, `Member`, `List`). `onMessage`에서 기존 `convertAndSend("/sub/chatrooms/…")` 직후 추가:
```java
            // 안읽음 fan-out: 방 멤버(보낸 사람 제외)에게 개인 큐로 통지. 각 서버가 자기 로컬 세션에 라우팅(멀티서버 안전).
            List<Member> members = chatRoomMemberRepository.findMembersByChatRoomId(messageResponse.getChatroomId());
            UnreadEvent event = new UnreadEvent(messageResponse.getChatroomId(), messageResponse.getMessageId());
            for (Member member : members) {
                if (member.getId().equals(messageResponse.getMemberId())) continue; // 보낸 사람 제외
                messagingTemplate.convertAndSendToUser(member.getEmail(), "/queue/unread", event);
            }
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests "*UnreadCountTest"` → PASS.

- [ ] **Step 5: 전체 백엔드 회귀 + Commit**
```bash
./gradlew test    # 전체 그린 확인
git add src/main/java/.../dto/UnreadEvent.java src/main/java/.../repository/ChatRoomMemberRepository.java src/main/java/.../redis/RedisSubscriber.java src/test/java/.../service/UnreadCountTest.java
git commit -m "feat(unread): 메시지 생성 시 방 멤버에게 실시간 unread 이벤트 fan-out"
```

---

## Task 6: 프론트 api — unread 조회·읽음 처리

**Files:**
- Modify: `frontend/src/lib/api.ts`

**Interfaces:**
- Produces: `interface UnreadCount { chatroomId: number; unreadCount: number; lastReadMessageId: number | null; }`; `getUnreadCounts(token): Promise<UnreadCount[]>`; `markRoomRead(token, chatroomId): Promise<void>`.

- [ ] **Step 1: 구현**

`api.ts`에 추가(적당한 위치, 예: `getMessages` 뒤):
```ts
export interface UnreadCount {
  chatroomId: number;
  unreadCount: number;
  lastReadMessageId: number | null;
}

export async function getUnreadCounts(token: string): Promise<UnreadCount[]> {
  return request<UnreadCount[]>('/api/chatrooms/unread', {}, token);
}

export async function markRoomRead(token: string, chatroomId: string): Promise<void> {
  return request<void>(`/api/chatrooms/${chatroomId}/read`, { method: 'POST' }, token);
}
```

- [ ] **Step 2: 검증 + Commit**
```bash
cd frontend && npm run lint && npm run build   # exit 0
git add frontend/src/lib/api.ts
git commit -m "feat(unread): 프론트 api getUnreadCounts/markRoomRead"
```

---

## Task 7: 프론트 stomp — /user/queue/unread 구독

**Files:**
- Modify: `frontend/src/lib/stomp.ts`

**Interfaces:**
- Consumes: `StompClientOptions`.
- Produces: `onUnread?(evt: { chatroomId: number; messageId: number }): void` 콜백. 연결 시 `/user/queue/unread`를 상시 구독하고, 수신 프레임을 `onUnread`로 라우팅.

- [ ] **Step 1: 구현**

`stomp.ts`:
- `StompClientOptions`에 `onUnread?: (evt: { chatroomId: number; messageId: number }) => void;` 추가.
- `subscriptionKinds` Map의 종류 타입에 `'unread'` 추가: `Map<string, 'chat' | 'typing' | 'roompresence' | 'unread'>`.
- 새 필드 `private unreadSubscription?: string;`
- CONNECTED 처리(`handleRawMessage`의 `if (frame.command === 'CONNECTED')`) 안, `this.options.onConnect();` **전에** 사용자 큐 구독:
```ts
        this.unreadSubscription = `sub-${++this.subscriptionId}`;
        this.subscriptionKinds.set(this.unreadSubscription, 'unread');
        this.write('SUBSCRIBE', {
          id: this.unreadSubscription,
          destination: '/user/queue/unread',
          ack: 'auto',
        });
```
- MESSAGE 라우팅(`kind` 분기)에 추가:
```ts
        } else if (kind === 'unread') {
          const p = JSON.parse(frame.body) as { chatroomId: number; messageId: number };
          this.options.onUnread?.(p);
```

- [ ] **Step 2: 검증 + Commit**
```bash
cd frontend && npm run lint && npm run build   # exit 0
git add frontend/src/lib/stomp.ts
git commit -m "feat(unread): stomp /user/queue/unread 상시 구독"
```

---

## Task 8: 프론트 App — unread 상태·이벤트·읽음처리·props 배선

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `getUnreadCounts`, `markRoomRead`, stomp `onUnread`.
- Produces: `unread: Record<string, number>` + `roomLastRead: Record<string, number|null>` 상태; `ChannelLanding`에 `unread` prop, `ChatArea`에 `lastReadSnapshot`(입장 시 그 방 lastRead) prop.

- [ ] **Step 1: 구현 — 상태·로드·이벤트**

`App.tsx`:
- import에 `getUnreadCounts, markRoomRead` 추가.
- 상태 추가(다른 useState 근처):
```ts
  const [unread, setUnread] = useState<Record<string, number>>({});
  const [roomLastRead, setRoomLastRead] = useState<Record<string, number | null>>({});
```
- 로그인 후(토큰·유저 준비되는 effect, 예: `refreshRooms` 하는 곳) unread 로드:
```ts
    const counts = await getUnreadCounts(token);
    setUnread(Object.fromEntries(counts.map((c) => [String(c.chatroomId), c.unreadCount])));
    setRoomLastRead(Object.fromEntries(counts.map((c) => [String(c.chatroomId), c.lastReadMessageId])));
```
- STOMP 클라 생성 옵션에 `onUnread` 추가(`onMessage` 근처):
```ts
        onUnread: ({ chatroomId }) => {
          const roomId = String(chatroomId);
          if (roomId === selectedChannelRef.current) return; // 지금 보는 방은 무시
          setUnread((prev) => ({ ...prev, [roomId]: (prev[roomId] ?? 0) + 1 }));
        },
```

- [ ] **Step 2: 구현 — 입장 시 읽음처리 + lastRead 스냅샷**

방 입장 effect(`loadMessages`가 있는 `[token, selectedChannelId]` effect) 안, `getMessages` 성공 후:
```ts
        // 입장 시 lastRead 스냅샷(구분선용) → 그 뒤 읽음 처리
        // roomLastRead[selectedChannelId]는 입장 직전 값 사용(스냅샷은 ChatArea에 prop으로 전달)
        await markRoomRead(token, selectedChannelId);
        setUnread((prev) => ({ ...prev, [selectedChannelId]: 0 }));
```
주의: `roomLastRead[selectedChannelId]`(스냅샷)는 markRoomRead **호출 전** 값이어야 함 → 상태를 바꾸지 말고 그대로 ChatArea에 prop 전달(아래).

- [ ] **Step 3: props 배선**

`<ChannelLanding ... />`에 `unread={unread}` 추가.
`<ChatArea ... />`에 추가:
```tsx
              unreadFromId={roomLastRead[selectedChannelId] ?? null}
```
(입장 시점의 lastRead = "이 id 다음부터 안읽음". markRoomRead가 DB를 갱신해도 이 prop 값은 그 세션 동안 안 바뀜 — App의 roomLastRead를 입장 시 갱신하지 않으므로.)

- [ ] **Step 4: 검증 + Commit**
```bash
cd frontend && npm run lint && npm run build   # exit 0
git add frontend/src/App.tsx
git commit -m "feat(unread): App unread 상태·실시간 이벤트·입장 읽음처리·props"
```

---

## Task 9: 프론트 ChannelLanding — 안읽음 배지

**Files:**
- Modify: `frontend/src/components/ChannelLanding.tsx`

**Interfaces:**
- Consumes: `unread: Record<string, number>` prop.

- [ ] **Step 1: 구현**

`ChannelLanding.tsx`:
- `Props`에 `unread: Record<string, number>;` 추가, 구조분해에 `unread` 추가.
- 우표 렌더(각 채널 `<div>` 안, 절대배치 우표 요소)에 배지 추가 — 우표 컨테이너(`className="stamp-in absolute ..."`) 안 최상단에:
```tsx
                {(unread[ch.id] ?? 0) > 0 && (
                  <div className="absolute -top-1.5 -right-1.5 z-30 min-w-[20px] h-5 px-1.5 rounded-full bg-rose-500 text-white text-[11px] font-bold flex items-center justify-center shadow-md">
                    {unread[ch.id] > 99 ? '99+' : unread[ch.id]}
                  </div>
                )}
```

- [ ] **Step 2: 하니스 검증**

임시 하니스로 `ChannelLanding`을 mock `channels` + `unread={{'2': 3}}`로 렌더 → 2번 우표에 "3" 배지 보이는지 스크린샷. `npm run lint && build` exit 0.

- [ ] **Step 3: Commit** (하니스 파일 삭제 후)
```bash
git add frontend/src/components/ChannelLanding.tsx
git commit -m "feat(unread): 랜딩 우표에 안읽음 배지"
```

---

## Task 10: 프론트 ChatArea — "여기부터 안읽음" 구분선 + 첫 안읽음 스크롤

**Files:**
- Modify: `frontend/src/components/ChatArea.tsx`

**Interfaces:**
- Consumes: `unreadFromId: number | null` prop (입장 시점 lastRead 스냅샷; 이 id **다음** 메시지부터 안읽음).

- [ ] **Step 1: 구현 — 구분선 렌더**

`ChatAreaProps`에 `unreadFromId?: number | null;` 추가, 구조분해 추가.
메시지 map 렌더에서, 각 메시지 바로 위에 "이 메시지가 첫 안읽음이면" 구분선:
```tsx
        {channelMessages.map((msg, i) => {
          const isFirstUnread =
            unreadFromId != null &&
            Number(msg.id) > unreadFromId &&
            (i === 0 || Number(channelMessages[i - 1].id) <= unreadFromId);
          return (
            <div key={msg.id}>
              {isFirstUnread && (
                <div className="flex items-center gap-2 my-3 select-none">
                  <div className="flex-1 h-px bg-rose-300" />
                  <span className="text-[11px] font-bold text-rose-500">여기부터 안 읽음</span>
                  <div className="flex-1 h-px bg-rose-300" />
                </div>
              )}
              {/* 기존 motion.div 메시지 블록 그대로 (key는 위 div로 옮김) */}
              ...
            </div>
          );
        })}
```
(주의: 기존 `motion.div`의 `key={msg.id}`는 바깥 `<div key={msg.id}>`로 이동. `channelMessages`는 오름차순이므로 `i-1`이 더 오래된 것.)

- [ ] **Step 2: 구현 — 입장 시 첫 안읽음으로 스크롤**

기존 입장 스크롤 effect(`scrolledChannelRef.current !== channel.id`일 때 `scrollToBottom`)를 보완: 첫 안읽음이 로드돼 있으면 그 요소로 스크롤, 없으면 맨아래.
```tsx
    if (scrolledChannelRef.current !== channel.id) {
      const firstUnread = unreadFromId != null
        ? channelMessages.find((m) => Number(m.id) > unreadFromId)
        : undefined;
      if (firstUnread) {
        document.getElementById(`message-bubble-${firstUnread.id}`)?.scrollIntoView({ block: 'start' });
      } else {
        scrollToBottom('auto');
      }
      if (channelMessages.length > 0) scrolledChannelRef.current = channel.id;
    } else {
      ... // 기존 새 메시지 따라가기 로직 그대로
    }
```
(안읽음이 30개 초과라 firstUnread가 로드 밖이면 `find`가 undefined → 맨아래 대신 "로드된 맨 위"가 더 맞지만, 이 경우 전부 안읽음이므로 맨아래로 가지 않게: `firstUnread` 없고 `unreadFromId != null && channelMessages.length && Number(channelMessages[0].id) > unreadFromId`이면 맨 위로. 아래 보강.)
보강:
```tsx
      const allLoadedUnread = unreadFromId != null && channelMessages.length > 0
        && Number(channelMessages[0].id) > unreadFromId;
      if (firstUnread) {
        document.getElementById(`message-bubble-${firstUnread.id}`)?.scrollIntoView({ block: 'start' });
      } else if (allLoadedUnread) {
        scrollContainerRef.current?.scrollTo({ top: 0 });     // 경계는 더 위(이전 페이지) — 맨 위로
      } else {
        scrollToBottom('auto');
      }
```

- [ ] **Step 3: 하니스 검증**

하니스로 ChatArea에 `messages`(id 1~10 오름차순) + `unreadFromId={5}` → (a) 6번 위에 "여기부터 안 읽음" 구분선, (b) 입장 시 6번으로 스크롤됨(6번이 뷰포트 상단 근처). `unreadFromId={null}`이면 구분선 없고 맨아래. `npm run lint && build` exit 0.

- [ ] **Step 4: Commit** (하니스 삭제 후)
```bash
git add frontend/src/components/ChatArea.tsx
git commit -m "feat(unread): 여기부터 안읽음 구분선 + 첫 안읽음 스크롤"
```

---

## Task 11: 통합 검증 + PR

- [ ] **Step 1: 백엔드 전체** — `./gradlew test` 그린. Flyway V2 로컬 MySQL 부팅 재확인(Task 1 Step 3 방식).
- [ ] **Step 2: 프론트** — `npm run lint && npm run build` exit 0.
- [ ] **Step 3: 하니스 종합** — (선택) App 흐름 모사: unread 이벤트→배지 증가(현재 방 제외), 입장→배지 0·구분선·스크롤.
- [ ] **Step 4: 스펙 문서 최신화** — 구현 중 벗어난 결정 있으면 `docs/superpowers/specs/2026-07-23-unread-count-design.md`에 정정 추가.
- [ ] **Step 5: PR → develop**
```bash
git push -u origin feat/unread-count
gh pr create --base develop --head feat/unread-count --title "feat(unread): 실시간 안읽음 카운트 + 안읽음부터 보기" --body "…(설계·검증·Flyway V2 자동적용 명시)…"
```
배포 시 Flyway가 **V2를 자동 적용**(수동 ALTER 없음).

---

## Self-Review 결과

- **Spec coverage**: §1 데이터모델→T1·T2, §2 백엔드(조회 T3·읽음 T4·fan-out T5), §3 프론트(api T6·stomp T7·App T8·배지 T9), §3.5 구분선·스크롤 T10, §5 검증 T1~T11, §6 의존성(Flyway 병합됨) 반영. 커버 확인.
- **Placeholder scan**: 없음(코드 제시). `…(설계·검증…)…`는 PR 본문 작성 지시라 실코드 아님.
- **Type consistency**: `UnreadCount{chatroomId, unreadCount, lastReadMessageId}` 백/프 일치. `onUnread({chatroomId, messageId})` stomp↔App 일치. `unreadFromId`(App→ChatArea) 일치. `lastReadMessageId`(응답)→`roomLastRead`→`unreadFromId` 흐름 일치.
