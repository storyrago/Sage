# 비공개방 1단계(인가 기반) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 방에 주인과 잠금을 도입해, 잠긴 방은 초대 코드를 가진 사람만 들어올 수 있게 한다.

**Architecture:** `chatrooms`에 `created_by`·`is_private`·`invite_code`·`deleted_at`을 더하고 `chatroom_bans` 테이블을 만든다(V7). 인가 판정은 새로 만들지 않고 기존 단일 지점 `RoomAccess.isMember`와 `ChatRoomService.getChatRoomById`에 조건을 얹는다. 방 생성은 서버가 생성자를 주인이자 첫 멤버로 등록하고, 입장은 기존 `join` 엔드포인트가 선택 필드 `inviteCode`를 받아 판정한다.

**Tech Stack:** Spring Boot 3, Spring Data JPA, Flyway, JUnit 5 + AssertJ, React 19 + TypeScript + Vite

**설계 문서:** [`docs/superpowers/specs/2026-08-05-private-rooms-design.md`](../specs/2026-08-05-private-rooms-design.md)

## Global Constraints

- **스키마 변경은 Flyway 마이그레이션으로만 한다.** 수동 ALTER 금지. `ddl-auto: validate`이며 배포 시 자동 적용된다. 다음 번호는 **V7**이다
- **테스트는 H2 create-drop이고 Flyway가 비활성이다**(`src/test/resources/application.yaml`). 테스트 스키마는 엔티티에서 생성되므로, 마이그레이션 SQL에만 존재하는 제약은 테스트가 검증하지 못한다
- **초대 코드는 12자**, 알파벳은 혼동 문자(`0 O 1 l I`)를 뺀 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`(32자), 난수원은 `SecureRandom`
- **초대 코드는 요청 본문·응답 본문에만 싣는다.** URL 경로·쿼리스트링·리다이렉트 `Location`·로그에 넣지 않는다. nginx가 `$request`와 `$http_referer`를 CloudWatch로 보낸다
- **`inviteCode`는 방 주인에게만 응답에 실린다.** 그 외에는 필드 자체를 내보내지 않는다
- **커밋 메시지·주석에 배경 서사를 쓰지 않는다.** 변경의 목적만 쓴다
- 백엔드 검증은 `./gradlew test`, 프론트는 `cd frontend && npm run lint && npm run build`
- 브랜치는 `feat/private-rooms`이며 이미 설계 문서 커밋이 올라가 있다

## 이번 단계에 없는 것

강퇴 API, 차단 해제 API, 코드 재발급, 공개↔비공개 전환, 방 삭제 API, 방장 패널 UI는 **2단계**다. 단 `chatroom_bans` 테이블과 `deleted_at` 컬럼은 이번 V7에서 함께 만들고, `join`의 차단 검사와 `RoomAccess`의 `deleted_at` 조건도 이번에 넣는다 — 마이그레이션을 두 번 나눌 이유가 없고, 검사를 미리 넣어두면 2단계가 API 추가만으로 끝난다.

---

### Task 1: V7 마이그레이션과 엔티티

**Files:**
- Create: `src/main/resources/db/migration/V7__private_rooms.sql`
- Create: `src/main/java/com/example/springboot_realtimechat/domain/ChatRoomBan.java`
- Create: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomBanRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/domain/ChatRoom.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/security/RoomAccessTest.java:36`
- Test: `src/test/java/com/example/springboot_realtimechat/room/ChatRoomEntityTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ChatRoom.publicRoom(String name, Member createdBy)` → `ChatRoom`
  - `ChatRoom.privateRoom(String name, Member createdBy, String inviteCode)` → `ChatRoom`
  - `ChatRoom.getCreatedBy()` → `Member` (nullable), `ChatRoom.isPrivate()` → `boolean`, `ChatRoom.getInviteCode()` → `String` (nullable), `ChatRoom.getDeletedAt()` → `LocalDateTime` (nullable)
  - `ChatRoomBanRepository.existsByChatRoomIdAndMemberId(Long chatRoomId, Long memberId)` → `boolean`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/room/ChatRoomEntityTest.java`

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomEntityTest {

    @Test
    void 공개방은_잠기지_않고_코드가_없다() {
        ChatRoom room = ChatRoom.publicRoom("공개방", null);

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
        assertThat(room.getCreatedBy()).isNull();
        assertThat(room.getDeletedAt()).isNull();
    }

    @Test
    void 비공개방은_잠기고_코드를_가진다() {
        ChatRoom room = ChatRoom.privateRoom("비공개방", null, "ABCDEFGHJKLM");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("ABCDEFGHJKLM");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*ChatRoomEntityTest*'`
Expected: 컴파일 실패 — `publicRoom`/`privateRoom` 심볼을 찾을 수 없음

- [ ] **Step 3: 마이그레이션을 쓴다**

`src/main/resources/db/migration/V7__private_rooms.sql`

```sql
-- 방의 주인. NULL이면 주인 없는 방(시드 방, 주인이 탈퇴한 방)이다.
ALTER TABLE chatrooms ADD COLUMN created_by BIGINT NULL;

-- 잠금 여부와 입장 코드. is_private=true 이고 invite_code IS NULL 이면
-- 아무도 새로 들어올 수 없는 동결 상태다.
ALTER TABLE chatrooms ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chatrooms ADD COLUMN invite_code VARCHAR(12) NULL;

-- 소프트 삭제 시각
ALTER TABLE chatrooms ADD COLUMN deleted_at DATETIME(6) NULL;

ALTER TABLE chatrooms
    ADD CONSTRAINT uk_chatrooms_invite_code UNIQUE (invite_code);
ALTER TABLE chatrooms
    ADD CONSTRAINT fk_chatrooms_created_by FOREIGN KEY (created_by) REFERENCES members (id);

-- 강퇴된 회원. 멤버십 행만 지우면 재입장으로 즉시 복구되므로 별도로 기록한다.
CREATE TABLE chatroom_bans (
    chatroom_id BIGINT      NOT NULL,
    member_id   BIGINT      NOT NULL,
    banned_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (chatroom_id, member_id),
    CONSTRAINT fk_bans_chatroom FOREIGN KEY (chatroom_id) REFERENCES chatrooms (id),
    CONSTRAINT fk_bans_member   FOREIGN KEY (member_id)   REFERENCES members (id)
);
```

- [ ] **Step 4: `ChatRoom` 엔티티를 고친다**

`src/main/java/com/example/springboot_realtimechat/domain/ChatRoom.java` — 필드와 정적 팩토리를 더하고 `public ChatRoom(String name)` 생성자를 **없앤다**. 잠금 상태와 코드가 어긋나지 못하게 생성 경로를 둘로 고정한다.

```java
package com.example.springboot_realtimechat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
@Table(name="chatrooms")
public class ChatRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length=100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    // 주인 없는 방이 정상 상태다. 시드 방과 주인이 탈퇴한 방이 여기 해당한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Member createdBy;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "invite_code", length = 12)
    private String inviteCode;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "chatRoom")
    private List<ChatRoomMember> chatRoomMembers = new ArrayList<>();

    @OneToMany(mappedBy = "chatRoom")
    private List<Message> messages = new ArrayList<>();

    private ChatRoom(String name, Member createdBy, boolean isPrivate, String inviteCode) {
        this.name = name;
        this.createdBy = createdBy;
        this.isPrivate = isPrivate;
        this.inviteCode = inviteCode;
    }

    public static ChatRoom publicRoom(String name, Member createdBy) {
        return new ChatRoom(name, createdBy, false, null);
    }

    public static ChatRoom privateRoom(String name, Member createdBy, String inviteCode) {
        return new ChatRoom(name, createdBy, true, inviteCode);
    }

    public boolean isPrivate() {
        return isPrivate;
    }
}
```

Lombok `@Getter`가 `boolean isPrivate`에 대해 `isPrivate()`를 만들지만, 명시적으로 두어 의도를 남긴다.

- [ ] **Step 5: `ChatRoomBan` 엔티티와 리포지토리를 만든다**

`src/main/java/com/example/springboot_realtimechat/domain/ChatRoomBan.java`

```java
package com.example.springboot_realtimechat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "chatroom_bans")
@IdClass(ChatRoomBan.Id.class)
public class ChatRoomBan {

    @jakarta.persistence.Id
    @Column(name = "chatroom_id")
    private Long chatRoomId;

    @jakarta.persistence.Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "banned_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime bannedAt;

    public ChatRoomBan(Long chatRoomId, Long memberId) {
        this.chatRoomId = chatRoomId;
        this.memberId = memberId;
    }

    @lombok.Getter
    @NoArgsConstructor
    public static class Id implements Serializable {
        private Long chatRoomId;
        private Long memberId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(chatRoomId, other.chatRoomId)
                    && Objects.equals(memberId, other.memberId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chatRoomId, memberId);
        }
    }
}
```

`src/main/java/com/example/springboot_realtimechat/repository/ChatRoomBanRepository.java`

```java
package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoomBan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomBanRepository extends JpaRepository<ChatRoomBan, ChatRoomBan.Id> {
    boolean existsByChatRoomIdAndMemberId(Long chatRoomId, Long memberId);
}
```

- [ ] **Step 6: 깨진 호출부를 고친다**

`RoomAccessTest.java:36`의 `new ChatRoom("방")`을 바꾼다.

```java
room = chatRoomRepository.save(ChatRoom.publicRoom("방", null));
```

`ChatRoomService.create`도 이 시점에 컴파일이 깨진다. 임시로 `ChatRoom.publicRoom(name, null)`을 쓰도록 고친다 — Task 3에서 제대로 바꾼다.

```java
@Transactional
public ChatRoom create(String name){
    ChatRoom chatRoom = ChatRoom.publicRoom(name, null);
    return chatRoomRepository.save(chatRoom);
}
```

- [ ] **Step 7: 테스트가 통과하는지 본다**

Run: `./gradlew test`
Expected: 전부 PASS. 실패하면 `new ChatRoom(` 호출부가 남아 있는 것이다 — `grep -rn "new ChatRoom(" src`로 찾는다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/resources/db/migration/V7__private_rooms.sql \
        src/main/java/com/example/springboot_realtimechat/domain/ChatRoom.java \
        src/main/java/com/example/springboot_realtimechat/domain/ChatRoomBan.java \
        src/main/java/com/example/springboot_realtimechat/repository/ChatRoomBanRepository.java \
        src/main/java/com/example/springboot_realtimechat/service/ChatRoomService.java \
        src/test/java/com/example/springboot_realtimechat/room/ChatRoomEntityTest.java \
        src/test/java/com/example/springboot_realtimechat/security/RoomAccessTest.java
git commit -m "feat(room): 방에 주인·잠금·소프트 삭제 컬럼과 차단 테이블을 더한다"
```

---

### Task 2: 초대 코드 생성기

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/service/InviteCodeGenerator.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/InviteCodeGeneratorTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `InviteCodeGenerator.generate()` → `String` (길이 12). 스프링 빈(`@Component`)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.service.InviteCodeGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    void 코드는_12자다() {
        assertThat(generator.generate()).hasSize(12);
    }

    @Test
    void 혼동되는_문자는_쓰지_않는다() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate()).doesNotContainAnyWhitespaces()
                    .matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{12}");
        }
    }

    @Test
    void 연달아_뽑아도_겹치지_않는다() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSize(1000);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*InviteCodeGeneratorTest*'`
Expected: 컴파일 실패 — `InviteCodeGenerator`를 찾을 수 없음

- [ ] **Step 3: 구현한다**

```java
package com.example.springboot_realtimechat.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 방 초대 코드를 만든다.
 * 코드 길이가 곧 보안이므로 줄이지 않는다 — 짧아지면 추측이 가능해져 시도 제한이 필요해진다.
 */
@Component
public class InviteCodeGenerator {

    // 0/O, 1/l/I 처럼 눈으로 구별하기 어려운 문자를 뺀 32자다.
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests '*InviteCodeGeneratorTest*'`
Expected: 3개 PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/InviteCodeGenerator.java \
        src/test/java/com/example/springboot_realtimechat/room/InviteCodeGeneratorTest.java
git commit -m "feat(room): 초대 코드 생성기를 추가한다"
```

---

### Task 3: 방 생성이 주인과 첫 멤버를 등록한다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomRepository.java`
- Modify: 테스트 46곳의 `chatRoomService.create("...")` 호출부
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomCreationTest.java`

**Interfaces:**
- Consumes: `ChatRoom.publicRoom`/`privateRoom` (Task 1), `InviteCodeGenerator.generate()` (Task 2)
- Produces:
  - `ChatRoomService.create(String name, boolean isPrivate, Long ownerId)` → `ChatRoom`. `ownerId`가 `null`이면 주인 없는 방
  - `ChatRoomService.getChatRoomById(Long id)` → `ChatRoom` — 삭제된 방은 `CHAT_ROOM_NOT_FOUND`
  - `ChatRoomService.getChatRoomByIdIncludingDeleted(Long id)` → `ChatRoom` — `leave` 전용
  - `ChatRoomService.getAllChatRooms()` → `List<ChatRoom>` — 삭제된 방 제외
  - `ChatRoomRepository.findByDeletedAtIsNull()` → `List<ChatRoom>`
  - `ChatRoomRepository.findByIdAndDeletedAtIsNull(Long id)` → `Optional<ChatRoom>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RoomCreationTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 생성자는_주인이자_첫_멤버가_된다() {
        Member owner = memberService.create("rc-owner@e.com", "1234", "주인");

        ChatRoom room = chatRoomService.create("새방", false, owner.getId());

        assertThat(room.getCreatedBy().getId()).isEqualTo(owner.getId());
        assertThat(chatRoomMemberRepository.existsByMemberIdAndChatRoomId(owner.getId(), room.getId()))
                .isTrue();
    }

    @Test
    void 비공개방은_코드를_받는다() {
        Member owner = memberService.create("rc-owner2@e.com", "1234", "주인2");

        ChatRoom room = chatRoomService.create("잠긴방", true, owner.getId());

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).hasSize(12);
    }

    @Test
    void 공개방은_코드를_받지_않는다() {
        Member owner = memberService.create("rc-owner3@e.com", "1234", "주인3");

        ChatRoom room = chatRoomService.create("열린방", false, owner.getId());

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
    }

    @Test
    void 삭제된_방은_목록과_단건_조회에서_빠진다() {
        Member owner = memberService.create("rc-owner4@e.com", "1234", "주인4");
        ChatRoom room = chatRoomService.create("지운방", false, owner.getId());
        chatRoomRepository.findById(room.getId()).ifPresent(r ->
                chatRoomRepository.save(markDeleted(r)));

        assertThat(chatRoomService.getAllChatRooms())
                .extracting(ChatRoom::getId)
                .doesNotContain(room.getId());
        assertThat(chatRoomService.getChatRoomByIdIncludingDeleted(room.getId()).getId())
                .isEqualTo(room.getId());
    }

    // 삭제 API는 2단계다. 여기서는 리플렉션 대신 리포지토리로 직접 값을 넣는다.
    private ChatRoom markDeleted(ChatRoom room) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                room, "deletedAt", java.time.LocalDateTime.now());
        return room;
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomCreationTest*'`
Expected: 컴파일 실패 — `create(String, boolean, Long)`과 `getChatRoomByIdIncludingDeleted`가 없음

- [ ] **Step 3: 리포지토리에 조회 메서드를 더한다**

`findAll()`을 `@Query`로 덮지 않는다. `findAll(Sort)`·`findAllById`·`count()`가 필터 없이 남아 다음 사람이 그중 하나를 집으면 삭제된 방이 조용히 되살아난다.

```java
package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    List<ChatRoom> findByDeletedAtIsNull();

    Optional<ChatRoom> findByIdAndDeletedAtIsNull(Long id);
}
```

- [ ] **Step 4: 서비스를 고친다**

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberRepository memberRepository;
    private final InviteCodeGenerator inviteCodeGenerator;

    // 코드 충돌은 UNIQUE 제약이 막는다. 확률이 극히 낮아 재시도 횟수는 작게 잡는다.
    private static final int CODE_RETRY = 5;

    /**
     * ownerId가 null이면 주인 없는 방을 만든다. 테스트 픽스처와 시드 방이 그 경우다.
     * 생성자는 주인이자 첫 멤버가 된다 — 잠긴 방에서는 별도 join이 코드 없이 거부되기 때문이다.
     */
    @Transactional
    public ChatRoom create(String name, boolean isPrivate, Long ownerId) {
        Member owner = ownerId == null ? null : memberRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        ChatRoom saved = saveWithCode(name, isPrivate, owner);

        if (owner != null) {
            chatRoomMemberRepository.save(new ChatRoomMember(owner, saved));
        }
        return saved;
    }

    private ChatRoom saveWithCode(String name, boolean isPrivate, Member owner) {
        if (!isPrivate) {
            return chatRoomRepository.save(ChatRoom.publicRoom(name, owner));
        }
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            try {
                // 코드는 로그에 남기지 않는다. 예외 메시지도 싣지 않고 조용히 재생성한다.
                return chatRoomRepository.saveAndFlush(
                        ChatRoom.privateRoom(name, owner, inviteCodeGenerator.generate()));
            } catch (DataIntegrityViolationException ignored) {
                // 코드가 겹쳤다. 다시 뽑는다.
            }
        }
        throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    public ChatRoom getChatRoomById(Long chatRoomId){
        return chatRoomRepository.findByIdAndDeletedAtIsNull(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /**
     * 삭제된 방도 돌려준다. leave 전용이다 —
     * 삭제된 방을 못 찾으면 그 방의 멤버십 행을 사용자가 영영 지울 수 없다.
     */
    public ChatRoom getChatRoomByIdIncludingDeleted(Long chatRoomId){
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public List<ChatRoom> getAllChatRooms(){
        return chatRoomRepository.findByDeletedAtIsNull();
    }
}
```

- [ ] **Step 5: 테스트 46곳의 호출부를 고친다**

전부 주인 없는 공개방이면 충분하다.

```bash
grep -rl 'chatRoomService\.create("' src/test/java \
  | xargs sed -i '' -E 's/chatRoomService\.create\((\"[^\"]*\")\)/chatRoomService.create(\1, false, null)/g'
grep -rn 'chatRoomService\.create(' src/test/java | grep -v 'false, null'
```

두 번째 명령이 아무것도 출력하지 않아야 한다.

- [ ] **Step 6: 컨트롤러 컴파일을 임시로 맞춘다**

`ChatRoomController.create`가 깨진다. Task 6에서 제대로 바꾸므로 여기서는 최소로 맞춘다.

```java
@PostMapping
public ChatRoomResponse create(@Valid @RequestBody ChatRoomRequest chatRoomRequest){
    ChatRoom chatRoom = chatRoomService.create(chatRoomRequest.getName(), false, null);
    return ChatRoomResponse.from(chatRoom);
}
```

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방 생성 시 생성자를 주인이자 첫 멤버로 등록한다"
```

---

### Task 4: 입장 판정 — 차단과 초대 코드

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`
- Create: `src/main/java/com/example/springboot_realtimechat/dto/RoomJoinRequest.java`
- Delete: `src/main/java/com/example/springboot_realtimechat/dto/ChatRoomMemberRequest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomMemberController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomJoinAuthorizationTest.java`

**Interfaces:**
- Consumes: `ChatRoomBanRepository.existsByChatRoomIdAndMemberId` (Task 1), `ChatRoomService.create(name, isPrivate, ownerId)` (Task 3)
- Produces: `ChatRoomMemberService.join(Long memberId, Long chatRoomId, String inviteCode)` → `ChatRoomMember`

`ChatRoomMemberRequest`는 `memberId`를 본문으로 받는 미사용 DTO다. **요청자는 언제나 JWT에서만 온다** — 본문의 어떤 필드도 신원으로 쓰지 않으므로 이번에 지운다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomBan;
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
class RoomJoinAuthorizationTest {

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
    void 공개방은_코드_없이_들어간다() {
        Member owner = memberService.create("j-owner@e.com", "1234", "주인");
        Member guest = memberService.create("j-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("공개", false, owner.getId());

        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void 잠긴_방은_코드가_맞아야_들어간다() {
        Member owner = memberService.create("j-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("j-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("잠김", true, owner.getId());

        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode()))
                .doesNotThrowAnyException();
    }

    @Test
    void 코드가_없으면_잠긴_방에_못_들어간다() {
        Member owner = memberService.create("j-owner3@e.com", "1234", "주인3");
        Member guest = memberService.create("j-guest3@e.com", "1234", "손님3");
        ChatRoom room = chatRoomService.create("잠김3", true, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 코드가_틀리면_못_들어간다() {
        Member owner = memberService.create("j-owner4@e.com", "1234", "주인4");
        Member guest = memberService.create("j-guest4@e.com", "1234", "손님4");
        ChatRoom room = chatRoomService.create("잠김4", true, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), "AAAAAAAAAAAA"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 동결된_방은_아무도_못_들어간다() {
        Member owner = memberService.create("j-owner5@e.com", "1234", "주인5");
        Member guest = memberService.create("j-guest5@e.com", "1234", "손님5");
        ChatRoom room = chatRoomService.create("동결", true, owner.getId());
        // 주인이 탈퇴한 방과 같은 상태를 만든다: 잠겨 있지만 코드가 없다.
        org.springframework.test.util.ReflectionTestUtils.setField(room, "inviteCode", null);
        chatRoomRepository.save(room);

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), "AAAAAAAAAAAA"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 차단된_사람은_코드를_알아도_못_들어간다() {
        Member owner = memberService.create("j-owner6@e.com", "1234", "주인6");
        Member banned = memberService.create("j-banned@e.com", "1234", "차단됨");
        ChatRoom room = chatRoomService.create("차단방", true, owner.getId());
        chatRoomBanRepository.save(new ChatRoomBan(room.getId(), banned.getId()));

        assertThatThrownBy(() -> chatRoomMemberService.join(banned.getId(), room.getId(), room.getInviteCode()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }

    @Test
    void 차단_검사가_중복_참여_검사보다_먼저다() {
        Member owner = memberService.create("j-owner7@e.com", "1234", "주인7");
        ChatRoom room = chatRoomService.create("순서방", false, owner.getId());
        chatRoomBanRepository.save(new ChatRoomBan(room.getId(), owner.getId()));

        assertThatThrownBy(() -> chatRoomMemberService.join(owner.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }
}
```

`CustomException`은 `getErrorCode()`를 노출하므로 `hasFieldOrPropertyWithValue("errorCode", ...)`가 그대로 동작한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomJoinAuthorizationTest*'`
Expected: 컴파일 실패 — `join(Long, Long, String)`과 새 `ErrorCode` 상수들이 없음

- [ ] **Step 3: `ErrorCode`를 더한다**

`// ChatRoomMember` 구획에 넣는다.

```java
    INVALID_INVITE_CODE(403, "초대 코드가 올바르지 않습니다."),
    ROOM_BANNED(403, "이 채팅방에 참여할 수 없습니다."),
    OWNER_CANNOT_LEAVE(409, "방장은 방을 나갈 수 없습니다. 방을 삭제해 주세요."),
```

- [ ] **Step 4: 요청 DTO를 만들고 미사용 DTO를 지운다**

`src/main/java/com/example/springboot_realtimechat/dto/RoomJoinRequest.java`

```java
package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 입장 요청 본문. 요청자는 언제나 JWT에서 오므로 신원 필드는 두지 않는다.
 */
@Getter
@Setter
public class RoomJoinRequest {
    private String inviteCode;
}
```

```bash
git rm src/main/java/com/example/springboot_realtimechat/dto/ChatRoomMemberRequest.java
```

`ChatRoomMemberController`의 해당 import도 지운다.

- [ ] **Step 5: 서비스 판정을 구현한다**

`ChatRoomMemberService`에 `ChatRoomBanRepository`를 주입하고 `join`을 바꾼다.

```java
    /**
     * 판정 순서가 중요하다. 차단이 가장 먼저다 —
     * 중복 참여 검사가 앞서면 차단된 기존 멤버가 ALREADY_JOINED로 통과해 보인다.
     */
    @Transactional
    public ChatRoomMember join(Long memberId, Long chatRoomId, String inviteCode){
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);

        if (chatRoomBanRepository.existsByChatRoomIdAndMemberId(chatRoomId, memberId)) {
            throw new CustomException(ErrorCode.ROOM_BANNED);
        }

        if (roomAccess.isMember(memberId, chatRoomId)) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

        if (chatRoom.isPrivate() && !matchesInviteCode(chatRoom, inviteCode)) {
            throw new CustomException(ErrorCode.INVALID_INVITE_CODE);
        }

        ChatRoomMember chatRoomMember = new ChatRoomMember(member, chatRoom);
        chatRoomMember.updateLastRead(messageRepository.findMaxIdByChatRoom(chatRoom));
        try{
            return chatRoomMemberRepository.saveAndFlush(chatRoomMember);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }
    }

    // 코드가 없는 잠긴 방(주인이 탈퇴한 동결 상태)은 어떤 입력으로도 열리지 않는다.
    private boolean matchesInviteCode(ChatRoom chatRoom, String inviteCode) {
        String actual = chatRoom.getInviteCode();
        if (actual == null || inviteCode == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                inviteCode.getBytes(StandardCharsets.UTF_8));
    }
```

import 추가: `java.security.MessageDigest`, `java.nio.charset.StandardCharsets`, `com.example.springboot_realtimechat.repository.ChatRoomBanRepository`.

- [ ] **Step 6: 컨트롤러가 본문을 받게 한다**

`@RequestBody(required = false)`가 핵심이다. 기본값이면 본문 없는 기존 프론트 호출이 `HttpMessageNotReadableException`을 내고, `GlobalExceptionHandler`에 그 핸들러가 없어 **500 + 스택트레이스 로깅**이 된다.

```java
        @PostMapping
        public ChatRoomMemberResponse join(
                        @PathVariable Long chatroomId,
                        @RequestBody(required = false) RoomJoinRequest request,
                        @AuthenticationPrincipal CustomUserDetails customUserDetails) {
                String inviteCode = request == null ? null : request.getInviteCode();
                ChatRoomMember chatRoomMember = chatRoomMemberService.join(
                                customUserDetails.getMemberId(), chatroomId, inviteCode);
                return ChatRoomMemberResponse.from(chatRoomMember);
        }
```

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS. 기존 `join(memberId, roomId)` 2인자 호출부가 남아 있으면 컴파일이 깨진다 — `grep -rn "\.join(" src`로 찾아 세 번째 인자에 `null`을 넣는다.

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 잠긴 방 입장에 초대 코드와 차단 검사를 적용한다"
```

---

### Task 5: 삭제된 방 차단과 방장 leave 금지

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/security/RoomAccess.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/DeletedRoomAccessTest.java`

**Interfaces:**
- Consumes: Task 1·3의 산출물
- Produces: `ChatRoomMemberRepository.existsActiveMembership(Long memberId, Long chatRoomId)` → `boolean`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DeletedRoomAccessTest {

    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 삭제된_방에서는_멤버십이_인정되지_않는다() {
        Member owner = memberService.create("d-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제방", false, owner.getId());
        assertThat(roomAccess.isMember(owner.getId(), room.getId())).isTrue();

        ReflectionTestUtils.setField(room, "deletedAt", LocalDateTime.now());
        chatRoomRepository.save(room);

        assertThat(roomAccess.isMember(owner.getId(), room.getId())).isFalse();
    }

    @Test
    void 삭제된_방에서도_나갈_수_있다() {
        Member owner = memberService.create("d-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("d-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("삭제방2", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        ReflectionTestUtils.setField(room, "deletedAt", LocalDateTime.now());
        chatRoomRepository.save(room);

        assertThatCode(() -> chatRoomMemberService.leave(guest.getId(), room.getId()))
                .doesNotThrowAnyException();
        assertThat(chatRoomMemberRepository.existsByMemberIdAndChatRoomId(guest.getId(), room.getId()))
                .isFalse();
    }

    @Test
    void 방장은_방을_나갈_수_없다() {
        Member owner = memberService.create("d-owner3@e.com", "1234", "주인3");
        ChatRoom room = chatRoomService.create("주인방", false, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.leave(owner.getId(), room.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);
    }

    @Test
    void 주인_없는_방은_아무나_나갈_수_있다() {
        Member guest = memberService.create("d-guest4@e.com", "1234", "손님4");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        assertThatCode(() -> chatRoomMemberService.leave(guest.getId(), room.getId()))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*DeletedRoomAccessTest*'`
Expected: `삭제된_방에서는_멤버십이_인정되지_않는다`와 `방장은_방을_나갈_수_없다`가 FAIL

- [ ] **Step 3: 리포지토리에 조인 쿼리를 더한다**

```java
    @Query("""
        SELECT COUNT(cm) > 0 FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        WHERE cm.member.id = :memberId
          AND r.id = :chatRoomId
          AND r.deletedAt IS NULL
    """)
    boolean existsActiveMembership(@Param("memberId") Long memberId,
                                   @Param("chatRoomId") Long chatRoomId);
```

- [ ] **Step 4: `RoomAccess`를 고친다**

```java
    @Transactional(readOnly = true)
    public boolean isMember(Long memberId, Long chatRoomId) {
        if (memberId == null || chatRoomId == null) {
            log.warn("멤버십 판정에 필요한 식별자가 없음: memberId={}, chatRoomId={}", memberId, chatRoomId);
            return false;
        }
        return chatRoomMemberRepository.existsActiveMembership(memberId, chatRoomId);
    }
```

- [ ] **Step 5: `leave`를 고친다**

```java
    @Transactional
    public void leave(Long memberId, Long chatRoomId){
        Member member = memberService.getMemberById(memberId);
        // 삭제된 방도 조회한다. 못 찾으면 멤버십 행이 영영 남는다.
        ChatRoom chatRoom = chatRoomService.getChatRoomByIdIncludingDeleted(chatRoomId);

        Member owner = chatRoom.getCreatedBy();
        if (owner != null && owner.getId().equals(memberId)) {
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE);
        }

        ChatRoomMember chatRoomMember = chatRoomMemberRepository
                .findByMemberAndChatRoom(member, chatRoom)
                        .orElseThrow(()->new CustomException(ErrorCode.NOT_JOINED_ROOM));

        chatRoomMemberRepository.delete(chatRoomMember);
        eventPublisher.publishEvent(new RoomLeftEvent(memberId, chatRoomId));
    }
```

`owner != null` 검사가 먼저다. 주인 없는 방에서 `owner.getId()`를 부르면 NPE 500이 나고 스택트레이스가 CloudWatch로 나간다.

- [ ] **Step 6: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 7: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 삭제된 방의 멤버십을 무효로 하고 방장 퇴장을 막는다"
```

---

### Task 6: 응답 DTO 계약

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/dto/ChatRoomResponse.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/dto/ChatRoomRequest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/ChatRoomResponseTest.java`

**Interfaces:**
- Consumes: Task 1·3의 산출물
- Produces:
  - `ChatRoomResponse.from(ChatRoom room, Long requesterId, boolean joined)` → `ChatRoomResponse`
  - `ChatRoomMemberRepository.findChatRoomIdsByMemberId(Long memberId)` → `List<Long>`

**단일 인자 `from(ChatRoom)`을 삭제한다.** 남겨두면 다음 사람이 그걸 집고, `inviteCode`가 전원에게 나간다. 비공개 설계 전체가 한 줄로 무너지는 자리다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.ChatRoomResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomResponseTest {

    private Member member(Long id) {
        Member m = new Member("a@e.com", "pw", "닉");
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    @Test
    void 주인에게만_초대_코드가_실린다() {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");

        assertThat(ChatRoomResponse.from(room, 1L, true).getInviteCode()).isEqualTo("ABCDEFGHJKLM");
        assertThat(ChatRoomResponse.from(room, 2L, true).getInviteCode()).isNull();
        assertThat(ChatRoomResponse.from(room, 2L, false).getInviteCode()).isNull();
    }

    @Test
    void 주인_없는_방은_아무도_주인이_아니다() {
        ChatRoom room = ChatRoom.publicRoom("시드방", null);

        ChatRoomResponse response = ChatRoomResponse.from(room, 1L, false);

        assertThat(response.isOwner()).isFalse();
        assertThat(response.getInviteCode()).isNull();
    }

    @Test
    void 잠금과_참여_여부가_실린다() {
        Member owner = member(1L);
        ChatRoom locked = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");
        ChatRoom open = ChatRoom.publicRoom("열림", owner);

        assertThat(ChatRoomResponse.from(locked, 2L, false).isLocked()).isTrue();
        assertThat(ChatRoomResponse.from(open, 2L, true).isLocked()).isFalse();
        assertThat(ChatRoomResponse.from(open, 2L, true).isJoined()).isTrue();
    }
}
```

`Member`의 공개 생성자는 `Member(String email, String password, String nickname)`이다(`domain/Member.java:58`). `id`는 `@GeneratedValue`라 영속화 전에는 `null`이므로 테스트에서 `ReflectionTestUtils`로 넣는다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*ChatRoomResponseTest*'`
Expected: 컴파일 실패 — `from(ChatRoom, Long, boolean)`이 없음

- [ ] **Step 3: DTO를 고친다**

```java
package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ChatRoomResponse {
    private final Long id;
    private final String name;
    private final LocalDateTime createdAt;
    private final boolean locked;
    private final boolean joined;
    private final boolean owner;

    // 주인이 아니면 필드 자체를 응답에서 뺀다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String inviteCode;

    private ChatRoomResponse(Long id, String name, LocalDateTime createdAt,
                             boolean locked, boolean joined, boolean owner, String inviteCode) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.locked = locked;
        this.joined = joined;
        this.owner = owner;
        this.inviteCode = inviteCode;
    }

    /**
     * 요청자를 반드시 받는다. 요청자 없는 팩토리를 두면 초대 코드가 전원에게 나간다.
     */
    public static ChatRoomResponse from(ChatRoom chatRoom, Long requesterId, boolean joined){
        Member createdBy = chatRoom.getCreatedBy();
        boolean owner = createdBy != null
                && createdBy.getId() != null
                && createdBy.getId().equals(requesterId);

        return new ChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getName(),
                chatRoom.getCreatedAt(),
                chatRoom.isPrivate(),
                joined,
                owner,
                owner ? chatRoom.getInviteCode() : null
        );
    }
}
```

- [ ] **Step 4: 요청 DTO에 `private`을 더한다**

```java
package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRoomRequest {
    @NotBlank
    private String name;

    private boolean isPrivate;
}
```

Lombok이 `boolean isPrivate` 필드에 대해 `isPrivate()`/`setPrivate()`를 만들므로 Jackson이 추론하는 와이어 이름은 `private`이다. 프론트도 `{ name, private }`으로 보낸다. **필드에 `@JsonProperty("private")`를 명시한다** — 나중에 필드명을 바꿔도 계약이 조용히 따라 바뀌지 않게 하려는 것이다.

- [ ] **Step 5: 컨트롤러를 고친다**

```java
    @PostMapping
    public ChatRoomResponse create(@Valid @RequestBody ChatRoomRequest chatRoomRequest,
                                   @AuthenticationPrincipal CustomUserDetails user){
        ChatRoom chatRoom = chatRoomService.create(
                chatRoomRequest.getName(), chatRoomRequest.isPrivate(), user.getMemberId());
        return ChatRoomResponse.from(chatRoom, user.getMemberId(), true);
    }

    @GetMapping
    public List<ChatRoomResponse> getChatRooms(@AuthenticationPrincipal CustomUserDetails user){
        Long requesterId = user.getMemberId();
        // 방마다 멤버십을 조회하면 방 수만큼 쿼리가 돈다. 한 번에 걷어 메모리에서 대조한다.
        Set<Long> joinedRoomIds = new HashSet<>(
                chatRoomMemberRepository.findChatRoomIdsByMemberId(requesterId));

        return chatRoomService.getAllChatRooms().stream()
                .map(room -> ChatRoomResponse.from(room, requesterId, joinedRoomIds.contains(room.getId())))
                .toList();
    }
```

`ChatRoomController`에 `ChatRoomMemberRepository`를 주입한다. import 추가: `java.util.HashSet`, `java.util.Set`.

- [ ] **Step 6: 리포지토리에 조회를 더한다**

```java
    @Query("SELECT cm.chatRoom.id FROM ChatRoomMember cm WHERE cm.member.id = :memberId")
    List<Long> findChatRoomIdsByMemberId(@Param("memberId") Long memberId);
```

- [ ] **Step 7: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방 응답에 잠금·참여·주인 여부를 싣고 초대 코드를 주인에게만 준다"
```

---

### Task 7: 주인이 탈퇴하면 방을 동결한다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/OwnerWithdrawalTest.java`

**Interfaces:**
- Consumes: Task 1·3의 산출물
- Produces: `ChatRoomRepository.releaseOwnedRooms(Long memberId)` → `int`

`ON DELETE SET NULL`을 쓰지 않는 이유: 그 제약은 Flyway/MySQL 경로에만 있고 테스트는 H2 `create-drop`이라 검증되지 않는다. Hibernate는 `ON DELETE`를 DDL에 넣지 않으므로 H2에는 평범한 FK만 생겨 **기존 탈퇴 테스트가 참조 무결성 위반으로 깨진다.** 이 레포의 기존 패턴도 앱 레벨 정리다(`messageRepository.anonymizeByMember`).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 커밋되는 테스트다. @Transactional을 붙이면 외래키 제약이 실제로 평가되지 않는다.
@SpringBootTest
class OwnerWithdrawalTest {

    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired MemberRepository memberRepository;

    @MockitoBean S3Service s3Service;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 방을_소유한_회원도_탈퇴할_수_있다() {
        Member owner = memberService.create("w-owner@e.com", "1234", "주인");
        chatRoomService.create("소유방", true, owner.getId());

        assertThatCode(() -> memberService.delete(owner.getId())).doesNotThrowAnyException();
    }

    @Test
    void 주인이_탈퇴하면_방은_남고_코드가_회수된다() {
        Member owner = memberService.create("w-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("w-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("소유방2", true, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode());

        memberService.delete(owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getCreatedBy()).isNull();
        assertThat(reloaded.getInviteCode()).isNull();
        assertThat(reloaded.isPrivate()).isTrue();
        assertThat(chatRoomMemberRepository.existsByMemberIdAndChatRoomId(guest.getId(), room.getId()))
                .isTrue();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*OwnerWithdrawalTest*'`
Expected: 첫 테스트가 참조 무결성 위반으로 FAIL

- [ ] **Step 3: 벌크 UPDATE를 더한다**

`ChatRoomRepository`

```java
    /**
     * 주인을 지우고 초대 코드를 회수한다. 잠금은 유지한다 —
     * 코드만 지우면 잠긴 방이 공개방이 되어 아무나 들어온다.
     * 결과는 아무도 새로 들어올 수 없는 동결 상태다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatRoom r SET r.createdBy = null, r.inviteCode = null WHERE r.createdBy.id = :memberId")
    int releaseOwnedRooms(@Param("memberId") Long memberId);
```

import 추가: `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`.

- [ ] **Step 4: `MemberService.delete`에 끼운다**

`chatRoomMemberRepository.deleteByMember(member)`보다 **먼저** 호출한다. 회원 삭제 전에 `chatrooms`의 참조가 끊겨 있어야 한다.

```java
        chatRoomRepository.releaseOwnedRooms(id);
        chatRoomMemberRepository.deleteByMember(member);
        messageRepository.anonymizeByMember(member);
        memberRepository.deleteById(id);
```

`MemberService`에 `ChatRoomRepository`를 주입한다.

- [ ] **Step 5: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 주인이 탈퇴하면 방의 주인과 초대 코드를 회수한다"
```

---

### Task 8: 정보 누출 두 곳을 막는다

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MessageService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/ChatRoomMemberRepository.java`
- Test: `src/test/java/com/example/springboot_realtimechat/room/RoomInfoLeakTest.java`

**Interfaces:**
- Consumes: Task 5의 `existsActiveMembership`
- Produces: 없음(기존 동작 수정)

`MessageService.update`/`delete`가 `getMessageById` → `requireSameRoom` → `requireMember` 순서라, 비멤버가 응답 코드로 방 소속을 구분할 수 있다 — 403이면 그 방 메시지, 404면 아니다. 메시지 id를 훑으면 잠긴 방의 활동 시점이 샌다. 순서를 바꾸면 막힌다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomInfoLeakTest {

    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 비멤버는_메시지의_방_소속을_응답_코드로_알아낼_수_없다() {
        Member insider = memberService.create("leak-in@e.com", "1234", "안");
        Member outsider = memberService.create("leak-out@e.com", "1234", "밖");
        ChatRoom secret = chatRoomService.create("비밀방", true, insider.getId());
        Message message = messageService.create("비밀", null, insider.getId(), secret.getId(), null);

        // 그 방에 실제로 있는 메시지도, 없는 id도 같은 오류여야 한다.
        assertThatThrownBy(() -> messageService.update(secret.getId(), message.getId(), outsider.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);

        assertThatThrownBy(() -> messageService.update(secret.getId(), 999999L, outsider.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }
}
```

시그니처는 `update(Long chatroomId, Long messageId, Long memberId, String content)`다(`MessageService.java:80`).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*RoomInfoLeakTest*'`
Expected: 두 번째 단언이 `MESSAGE_NOT_FOUND`를 받아 FAIL

- [ ] **Step 3: 판정 순서를 바꾼다**

현재 `requireMember`는 `Message`에서 방 id를 꺼내므로(`MessageService.java:123`) 메시지를 먼저 조회해야만 부를 수 있다. 방 id를 직접 받게 바꿔야 순서를 앞당길 수 있다.

```java
    private void requireMember(Long memberId, Long chatroomId) {
        if (!roomAccess.isMember(memberId, chatroomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
    }
```

`update`(80~83행)와 `delete`(100~102행) 양쪽을 이 순서로 바꾼다.

```java
        requireMember(memberId, chatroomId);
        Message message = getMessageById(messageId);
        requireSameRoom(message, chatroomId);
```

`requireMember(memberId, message)` 형태의 다른 호출부가 남아 있으면 함께 고친다 — `grep -n "requireMember(" src/main/java/com/example/springboot_realtimechat/service/MessageService.java`로 확인한다.

- [ ] **Step 4: 안읽음 쿼리에 조인을 더한다**

삭제된 방 정보를 응답에 싣지 않는다.

```java
    @Query("""
        SELECT cm.chatRoom.id AS chatroomId,
               cm.lastReadMessageId AS lastReadMessageId,
               COUNT(m) AS unreadCount
        FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        LEFT JOIN Message m
            ON m.chatRoom = cm.chatRoom
           AND (m.member IS NULL OR m.member <> cm.member)
           AND m.deleted = false
           AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
        WHERE cm.member.id = :memberId
          AND r.deletedAt IS NULL
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);
```

**`findMembersByChatRoomId`에는 조건을 넣지 않는다.** 2단계의 삭제 통지 리스너가 이 쿼리로 회수 대상을 찾는데, 조건이 있으면 `AFTER_COMMIT` 시점에 항상 빈 리스트가 되어 구독 회수가 통째로 무동작이 된다.

- [ ] **Step 5: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat(room): 방 소속과 삭제된 방 정보가 응답으로 새지 않게 한다"
```

---

### Task 9: 프론트 — 타입과 방 생성

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/types.ts:25` (`Channel` 인터페이스)
- Modify: `frontend/src/components/ChannelLanding.tsx`

**Interfaces:**
- Consumes: Task 4·6의 API 계약
- Produces:
  - `createChatRoom(token: string, name: string, isPrivate: boolean)` → `Promise<BackendChatRoom>`
  - `joinChatRoom(token: string, chatroomId: string, inviteCode?: string)` → `Promise<void>`
  - `Channel` 타입에 `locked: boolean`, `joined: boolean`, `owner: boolean`, `inviteCode?: string`

- [ ] **Step 1: API 계층을 고친다**

`frontend/src/lib/api.ts`

```ts
export async function createChatRoom(token: string, name: string, isPrivate: boolean) {
  return request<BackendChatRoom>('/api/chatrooms', {
    method: 'POST',
    body: JSON.stringify({ name, private: isPrivate }),
  }, token);
}

export async function joinChatRoom(token: string, chatroomId: string, inviteCode?: string) {
  try {
    await request(`/api/chatrooms/${chatroomId}/members`, {
      method: 'POST',
      body: JSON.stringify({ inviteCode: inviteCode ?? null }),
    }, token);
  } catch (error) {
    // 이미 참여 중인 방에 다시 들어가는 것은 실패가 아니다.
    if (error instanceof ApiError && error.code === 'ALREADY_JOINED_ROOM') return;
    throw error;
  }
}
```

`BackendChatRoom` 타입에 `locked: boolean`, `joined: boolean`, `owner: boolean`, `inviteCode?: string`를 더한다.

- [ ] **Step 2: `toChannel` 매핑을 넓힌다**

`App.tsx`의 `toChannel`이 새 필드를 `Channel`로 옮기게 한다. `Channel` 타입 정의에도 같은 필드를 더한다.

- [ ] **Step 3: 생성 흐름에 비공개를 전달한다**

`App.tsx`의 `onCreateChannel`이 `isPrivate`을 받아 넘기게 바꾼다.

```tsx
onCreateChannel={async (name, isPrivate) => {
  if (!token) return;
  const room = await createChatRoom(token, name, isPrivate);
  try {
    await refreshRooms(token);
  } catch (refreshError) {
    console.error('[Channel] 생성 후 목록 갱신 실패(무시하고 계속):', refreshError);
  }
  setSelectedChannelId(String(room.id));
}}
```

- [ ] **Step 4: 생성 UI에 체크박스를 더한다**

`ChannelLanding.tsx`의 방 만들기 입력에 "비공개 방으로 만들기" 체크박스를 더하고, `onCreateChannel(name, isPrivate)`로 넘긴다. 생성 결과가 `locked`이면 초대 코드를 보여준다 — 주인만 응답에 코드를 받으므로 여기서만 볼 수 있다.

- [ ] **Step 5: 검증한다**

Run: `cd frontend && npm run lint && npm run build`
Expected: 둘 다 exit 0

- [ ] **Step 6: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 방을 만들 때 비공개 여부를 고를 수 있게 한다"
```

---

### Task 10: 프론트 — 잠긴 방 입장

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/ChannelLanding.tsx`

**Interfaces:**
- Consumes: Task 9의 `joinChatRoom(token, id, inviteCode?)`, `Channel.locked`/`joined`
- Produces: 없음

- [ ] **Step 1: 잠긴 우표를 표시한다**

`ChannelLanding.tsx`에서 `channel.locked && !channel.joined`이면 우표에 자물쇠를 그리고, 입장 버튼 대신 코드 입력을 띄운다.

- [ ] **Step 2: 코드 입력을 배선한다**

코드를 받아 `setSelectedChannelId` 대신 먼저 `joinChatRoom(token, id, code)`를 부른다. 성공하면 `refreshRooms` 후 입장하고, `INVALID_INVITE_CODE`면 입력 옆에 인라인 오류를 띄운다(토스트가 아니라 인라인이어야 어느 방의 코드가 틀렸는지 알 수 있다).

`ROOM_BANNED`는 별도 문구로 안내한다.

- [ ] **Step 3: `enterRoom`이 이미 참여한 방만 자동 입장하게 한다**

`App.tsx`의 `enterRoom`은 방 선택 시마다 `joinChatRoom`을 코드 없이 부른다. 잠긴 방을 코드 없이 선택하면 `INVALID_INVITE_CODE`가 나면서 랜딩으로 되돌아간다 — 동작은 안전하지만 사용자에게 혼란스럽다. 랜딩에서 이미 코드 입력을 거치므로, `selectedChannelId`가 설정되는 시점에는 항상 멤버다. `enterRoom`의 `joinChatRoom` 호출은 그대로 두되(공개방 자동 입장이 여기 의존한다), 실패 시 안내 문구가 `INVALID_INVITE_CODE`일 때 "초대 코드가 필요한 방이에요"로 나가게 한다.

- [ ] **Step 4: 검증한다**

Run: `cd frontend && npm run lint && npm run build`
Expected: 둘 다 exit 0

- [ ] **Step 5: 실제 브라우저로 확인한다**

`.claude/launch.json`의 dev 서버를 띄우고 백엔드를 로컬에서 기동한다(`JWT_SECRET` 환경변수가 없으면 부팅에 실패한다). 확인할 것:

- 공개방을 만들면 코드가 안 나온다
- 비공개방을 만들면 코드가 나오고, 로그아웃 후 다른 계정으로 보면 자물쇠만 보이고 코드는 안 보인다
- 틀린 코드는 인라인 오류, 맞는 코드는 입장
- 브라우저 개발자도구 네트워크 탭에서 `GET /api/chatrooms` 응답에 **비멤버 방의 `inviteCode` 키가 없는지** 확인한다

- [ ] **Step 6: 커밋**

```bash
git add frontend/src
git commit -m "feat(frontend): 잠긴 방에 초대 코드로 입장한다"
```

---

## 완료 기준

- `./gradlew test` 전부 통과
- `cd frontend && npm run lint && npm run build` exit 0
- Flyway V1~V7 fresh 부팅 확인 (throwaway DB에 `ddl-auto: validate`로 기동)
- `GET /api/chatrooms` 응답에서 비멤버 방에 `inviteCode` 키가 없음을 실제 응답으로 확인
- 시드 방(`created_by` NULL)에서 `leave`가 500이 아니라 정상 동작함을 확인

## 2단계로 넘기는 것

강퇴 API와 차단 등록, 차단 해제 API, 코드 재발급, 공개↔비공개 전환, 방 삭제 API, `RoomDeletedEvent`와 사유 전달 시그니처 변경(`revokeRoom(memberId, roomId, reason)`), `ROOM_DELETED`·`ROOM_KICKED` ErrorCode, 프론트 방장 패널과 통지 처리, `refreshRooms` 호출 지점 추가.
