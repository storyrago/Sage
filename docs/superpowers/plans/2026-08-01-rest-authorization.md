# REST 인가 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** REST의 멤버십 판정을 WebSocket과 같은 함수(`RoomAccess`)에 태우고, 응답에서 필요 없는 개인정보를 뺀다.

**Architecture:** 안 쓰는 조회 엔드포인트는 인가를 붙이지 않고 지운다. 남는 곳(방 참여자 목록, 메시지 수정·삭제)은 서비스 계층에서 `RoomAccess.isMember`를 통과시킨다. 메시지 인가의 판정 축은 **메시지 엔티티의 방**이고, URL의 방이 다르면 그 자체로 거부한다. 타인 조회 응답은 email이 없는 별도 DTO로 분리한다.

**Tech Stack:** Spring Boot 4.0.5, Spring Security 7.0.4, JUnit 5, MockMvc, H2, React + TypeScript

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-08-01-rest-authorization-design.md`
- **멤버십 판정은 `RoomAccess.isMember(Long memberId, Long chatRoomId)` 하나만 쓴다.** 새 검사 로직을 만들지 않고, 기존 `existsByMemberAndChatRoom` 직접 호출도 이것으로 교체한다
- **검사는 서비스 계층에 둔다.** 컨트롤러에 두면 같은 서비스를 부르는 다른 경로가 열린 채로 남는다
- **메시지 인가의 판정 축은 메시지 엔티티의 방이다.** URL의 `chatroomId`를 기준으로 검사하면 가짜 게이트가 된다 — 전파 목적지는 `MessageResponse.from(message)`가 채운 엔티티의 방이기 때문이다
- 거부 코드: 비멤버는 `NOT_JOINED_ROOM`(403), URL 방과 엔티티 방 불일치는 `MESSAGE_NOT_FOUND`(404), 작성자 아님은 `NOT_MESSAGE_OWNER`(403). **새 `ErrorCode`를 만들지 않는다**
- 검사 순서: 메시지 로드 → URL 방 일치 → 작성자 → 멤버십
- `/me`·`PATCH /me`·온보딩·프로필사진 응답은 기존 `MemberResponse`(email 포함)를 그대로 쓴다. email을 빼는 것은 타인 조회 응답뿐이다
- 스키마 변경 없음. Flyway 마이그레이션을 추가하지 않는다
- 새 의존성 없음(`spring-security-test`도 추가하지 않는다)
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm run lint && npm test && npm run build`
- 브랜치: develop에서 `feat/rest-authorization`을 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사를 넣지 않는다

## File Structure

| 파일 | 변경 |
|---|---|
| `controller/MemberController.java` | `GET /api/members` 삭제, `GET /{id}`가 `PublicMemberResponse` 반환 |
| `service/MemberService.java` | `getMemberList()` 삭제 |
| `controller/ChatRoomController.java` | `GET /{id}` 삭제 |
| `service/MessageService.java` | `getAllChatRoomMessages` 삭제, `update`·`delete`에 방 인자·멤버십 추가, `create`·`getMessages`를 `RoomAccess`로 교체 |
| `controller/MessageController.java` | 수정·삭제에 `chatroomId` 전달 |
| `service/ChatRoomMemberService.java` | `getChatRoomMembersById(chatRoomId, requesterId)` + 멤버십 검사 |
| `controller/ChatRoomMemberController.java` | 참여자 목록에 `@AuthenticationPrincipal` 추가 |
| `dto/PublicMemberResponse.java` (신규) | 타인 조회용 `(id, nickname, profileImageUrl, createdAt)` |
| `frontend/src/lib/api.ts` | `getMemberById` 반환 타입 분리 |
| `src/test/.../MessageServiceTest.java` | `getAllChatRoomMessages` 사용부 교체 |
| `src/test/.../ChatRoomMemberN1Test.java` | 시그니처 변경 반영 |

---

### Task 1: 안 쓰는 조회 경로 삭제

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/MemberController.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MessageService.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/service/MessageServiceTest.java`
- Test: `src/test/java/com/example/springboot_realtimechat/security/DeletedEndpointsTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces: 없음. 이후 태스크는 이 세 경로가 사라진 상태를 전제한다

**배경:** `GET /api/members`는 인증만 하면 전 회원의 email을 반환하고, `GET /api/chatrooms/{id}`와 `MessageService.getAllChatRoomMessages`는 프로덕션 호출부가 없다. 인가를 붙이는 대신 지워서 공격면을 없앤다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git checkout develop && git pull && git checkout -b feat/rest-authorization
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/security/DeletedEndpointsTest.java`:

```java
package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 인가를 붙이는 대신 지운 경로가 다시 살아나지 않게 고정한다. */
@SpringBootTest
class DeletedEndpointsTest {

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    /** "GET /api/members" 형태의 문자열 집합 */
    private Set<String> mappedEndpoints() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(this::describe)
                .collect(Collectors.toSet());
    }

    private java.util.stream.Stream<String> describe(RequestMappingInfo info) {
        Set<String> patterns = info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
        return patterns.stream().flatMap(pattern ->
                info.getMethodsCondition().getMethods().stream()
                        .map(method -> method.name() + " " + pattern));
    }

    @Test
    void 전체_회원_목록_경로가_없다() {
        assertThat(mappedEndpoints()).doesNotContain("GET /api/members");
    }

    @Test
    void 방_단건_조회_경로가_없다() {
        assertThat(mappedEndpoints()).doesNotContain("GET /api/chatrooms/{id}");
    }

    @Test
    void 남아있어야_할_경로는_그대로다() {
        assertThat(mappedEndpoints())
                .contains("GET /api/members/{id}", "GET /api/members/me", "GET /api/chatrooms");
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*DeletedEndpointsTest*'
```

기대: `전체_회원_목록_경로가_없다`와 `방_단건_조회_경로가_없다`가 FAIL. `남아있어야_할_경로는_그대로다`는 PASS.

세 번째 테스트가 실패하면 경로 문자열 형식이 예상과 다른 것이다. 실패 메시지에 실제 집합이 찍히므로 그 형식에 맞춰 위 문자열을 고친다(테스트를 지우지 말 것).

- [ ] **Step 4: `GET /api/members` 삭제**

`MemberController.java`에서 아래 메서드 전체를 지운다.

```java
    @GetMapping
    public List<MemberResponse> getAllMembers(){
        List<Member> memberList = memberService.getMemberList();
        return memberList.stream()
                .map(MemberResponse::from)
                .toList();
    }
```

같은 파일에서 이제 쓰이지 않는 import를 지운다.

```java
import java.util.List;
```

- [ ] **Step 5: `getMemberList()` 삭제**

`MemberService.java`에서 아래 메서드와 이제 쓰이지 않는 import를 지운다.

```java
    public List<Member> getMemberList(){
        return memberRepository.findAll();
    }
```

```java
import java.util.List;
```

- [ ] **Step 6: `GET /api/chatrooms/{id}` 삭제**

`ChatRoomController.java`에서 아래 메서드 전체를 지운다. **`import java.util.List;`는 목록 조회가 계속 쓰므로 남긴다.**

```java
    @GetMapping("/{id}")
    public ChatRoomResponse getChatRoom(@PathVariable Long id){
        ChatRoom chatRoom = chatRoomService.getChatRoomById(id);
        return ChatRoomResponse.from(chatRoom);
    }
```

- [ ] **Step 7: `getAllChatRoomMessages` 삭제와 테스트 교체**

`MessageService.java`에서 아래 메서드를 지운다.

```java
    public List<Message> getAllChatRoomMessages(Long chatroomId){
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);
        return messageRepository.findByChatRoomOrderById(chatRoom);
    }
```

`MessageServiceTest.java`의 `메시지_생성_및_조회`에서 조회 부분을 멤버십을 검사하는 API로 바꾼다. 아래 한 줄을

```java
        List<Message> messages = messageService.getAllChatRoomMessages(chatRoom.getId());
```

이렇게 바꾼다.

```java
        List<Message> messages =
                messageService.getMessages(chatRoom.getId(), member.getId(), null, 30).messages();
```

`getMessages`는 오름차순(오래된 → 최신)으로 돌려주므로 뒤따르는 단언 3개는 그대로 통과한다.

- [ ] **Step 8: 테스트 통과 확인**

```bash
./gradlew test --tests '*DeletedEndpointsTest*'
```

기대: PASS — 3 tests

- [ ] **Step 9: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. `MessageRepository.findByChatRoomOrderById`가 이제 아무도 안 쓰더라도 이번 태스크에서는 지우지 않는다(범위 밖).

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/controller/MemberController.java src/main/java/com/example/springboot_realtimechat/service/MemberService.java src/main/java/com/example/springboot_realtimechat/controller/ChatRoomController.java src/main/java/com/example/springboot_realtimechat/service/MessageService.java src/test/java/com/example/springboot_realtimechat/service/MessageServiceTest.java src/test/java/com/example/springboot_realtimechat/security/DeletedEndpointsTest.java
git commit -m "refactor(api): 사용하지 않는 회원 목록과 방 단건 조회 경로 제거"
```

---

### Task 2: 방 참여자 목록에 멤버십 요구

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatRoomMemberController.java`
- Modify: `src/test/java/com/example/springboot_realtimechat/service/ChatRoomMemberN1Test.java`
- Test: `src/test/java/com/example/springboot_realtimechat/service/RoomRosterAuthorizationTest.java` (신규)

**Interfaces:**
- Consumes: `RoomAccess#isMember(Long memberId, Long chatRoomId)` — 이미 존재하는 컴포넌트다
- Produces: `ChatRoomMemberService#getChatRoomMembersById(Long chatRoomId, Long requesterId)` — 인자가 하나 늘어난다

**배경:** 컨트롤러가 `@AuthenticationPrincipal`을 아예 받지 않아, 인증만 하면 입장하지 않은 방의 참여자 명단을 수집할 수 있다. WS는 같은 정보를 멤버십으로 막는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/service/RoomRosterAuthorizationTest.java`:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RoomRosterAuthorizationTest {

    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;

    private Member member;
    private Member outsider;
    private Long roomId;

    @BeforeEach
    void setUp() {
        member = memberService.create("roster-member@test.com", "1234", "멤버");
        outsider = memberService.create("roster-outsider@test.com", "1234", "비멤버");
        ChatRoom room = chatRoomService.create("명단방");
        roomId = room.getId();
        chatRoomMemberService.join(member.getId(), roomId);
    }

    @Test
    void 멤버는_참여자_목록을_본다() {
        assertThat(chatRoomMemberService.getChatRoomMembersById(roomId, member.getId()))
                .hasSize(1);
    }

    @Test
    void 비멤버는_참여자_목록을_보지_못한다() {
        assertThatThrownBy(() -> chatRoomMemberService.getChatRoomMembersById(roomId, outsider.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 방을_나가면_참여자_목록을_보지_못한다() {
        chatRoomMemberService.leave(member.getId(), roomId);

        assertThatThrownBy(() -> chatRoomMemberService.getChatRoomMembersById(roomId, member.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }
}
```

`CustomException`은 `private final ErrorCode errorCode`와 `getErrorCode()`를 가지므로 `hasFieldOrPropertyWithValue("errorCode", ...)`가 그대로 동작한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*RoomRosterAuthorizationTest*'
```

기대: 컴파일 실패 — `getChatRoomMembersById(Long, Long)` 시그니처가 없다.

- [ ] **Step 3: 서비스에 멤버십 검사 추가**

`ChatRoomMemberService.java`의 필드에 `RoomAccess`를 추가한다(`@RequiredArgsConstructor`이므로 필드 선언만 추가하면 된다).

```java
    private final RoomAccess roomAccess;
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.security.RoomAccess;
```

기존 메서드를 아래로 바꾼다.

```java
    public List<ChatRoomMember> getChatRoomMembersById(Long chatRoomId, Long requesterId){
        if (!roomAccess.isMember(requesterId, chatRoomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);

        return chatRoomMemberRepository.findByChatRoom(chatRoom);
    }
```

- [ ] **Step 4: 컨트롤러가 호출자를 넘기게 한다**

`ChatRoomMemberController.java`의 참여자 목록 조회를 아래로 바꾼다.

```java
        @GetMapping
        public List<ChatRoomMemberResponse> getAllChatRoomMembers(
                        @PathVariable Long chatroomId,
                        @AuthenticationPrincipal CustomUserDetails customUserDetails) {
                List<ChatRoomMember> chatRoomMemberList =
                                chatRoomMemberService.getChatRoomMembersById(chatroomId, customUserDetails.getMemberId());
                return chatRoomMemberList.stream()
                                .map(ChatRoomMemberResponse::from)
                                .toList();
        }
```

`@AuthenticationPrincipal`과 `CustomUserDetails` import가 이미 있는지 확인하고, 없으면 추가한다.

```java
import com.example.springboot_realtimechat.security.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
```

- [ ] **Step 5: 기존 테스트의 호출부 갱신**

`ChatRoomMemberN1Test.java`의 아래 한 줄을

```java
        List<ChatRoomMember> members = chatRoomMemberService.getChatRoomMembersById(room.getId());
```

이렇게 바꾼다. 이 테스트의 `a`는 그 방의 멤버이므로 검사를 통과한다.

```java
        List<ChatRoomMember> members = chatRoomMemberService.getChatRoomMembersById(room.getId(), a.getId());
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests '*RoomRosterAuthorizationTest*'
```

기대: PASS — 3 tests

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/ChatRoomMemberService.java src/main/java/com/example/springboot_realtimechat/controller/ChatRoomMemberController.java src/test/java/com/example/springboot_realtimechat/service/ChatRoomMemberN1Test.java src/test/java/com/example/springboot_realtimechat/service/RoomRosterAuthorizationTest.java
git commit -m "feat(authz): 방 참여자 목록 조회에 멤버십 요구"
```

---

### Task 3: 메시지 수정·삭제에 멤버십과 방 일치 요구

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MessageService.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/MessageController.java`
- Test: `src/test/java/com/example/springboot_realtimechat/service/MessageAuthorizationTest.java` (신규)

**Interfaces:**
- Consumes: `RoomAccess#isMember(Long memberId, Long chatRoomId)`
- Produces: `MessageService#update(Long chatroomId, Long messageId, Long memberId, String content)`, `MessageService#delete(Long chatroomId, Long messageId, Long memberId)` — 방 인자가 **맨 앞에** 추가된다

**배경:** 지금은 작성자 검사만 한다. 방을 나간 뒤에도 그 방의 자기 메시지를 고치고 지울 수 있고, 결과가 그 방으로 실시간 전파된다. 전파 목적지는 URL이 아니라 엔티티의 방이므로, 인가도 엔티티의 방을 기준으로 해야 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/service/MessageAuthorizationTest.java`:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MessageAuthorizationTest {

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private Member author;
    private Member otherMember;
    private Long roomId;
    private Long otherRoomId;
    private Message message;

    @BeforeEach
    void setUp() {
        author = memberService.create("msg-author@test.com", "1234", "작성자");
        otherMember = memberService.create("msg-other@test.com", "1234", "다른멤버");

        ChatRoom room = chatRoomService.create("대상방");
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId);
        chatRoomMemberService.join(otherMember.getId(), roomId);

        ChatRoom otherRoom = chatRoomService.create("공격자방");
        otherRoomId = otherRoom.getId();
        chatRoomMemberService.join(author.getId(), otherRoomId);

        message = messageService.create("원본", null, author.getId(), roomId, null);
    }

    @Test
    void 멤버인_작성자는_수정한다() {
        Message updated = messageService.update(roomId, message.getId(), author.getId(), "고침");

        assertThat(updated.getContent()).isEqualTo("고침");
    }

    @Test
    void 방을_나가면_자기_메시지도_수정하지_못한다() {
        chatRoomMemberService.leave(author.getId(), roomId);

        assertThatThrownBy(() -> messageService.update(roomId, message.getId(), author.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 방을_나가면_자기_메시지도_삭제하지_못한다() {
        chatRoomMemberService.leave(author.getId(), roomId);

        assertThatThrownBy(() -> messageService.delete(roomId, message.getId(), author.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 자기가_속한_방_id를_붙여도_다른_방_메시지는_수정하지_못한다() {
        assertThatThrownBy(() -> messageService.update(otherRoomId, message.getId(), author.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void 존재하지_않는_방_id를_붙여도_거부된다() {
        assertThatThrownBy(() -> messageService.update(999999L, message.getId(), author.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void 작성자가_아닌_멤버는_수정하지_못한다() {
        assertThatThrownBy(() -> messageService.update(roomId, message.getId(), otherMember.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_MESSAGE_OWNER);
    }

    @Test
    void 멤버인_작성자는_삭제한다() {
        Message deleted = messageService.delete(roomId, message.getId(), author.getId());

        assertThat(deleted.isDeleted()).isTrue();
    }
}
```

`Message#isDeleted`는 기존 `MessageService.update`가 이미 호출하고 있는 접근자다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*MessageAuthorizationTest*'
```

기대: 컴파일 실패 — `update(Long, Long, Long, String)` 시그니처가 없다.

- [ ] **Step 3: 서비스에 방 일치와 멤버십 검사 추가**

`MessageService.java`의 필드에 `RoomAccess`를 추가한다.

```java
    private final RoomAccess roomAccess;
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.security.RoomAccess;
```

`update`와 `delete`를 아래로 바꾼다. 검사 순서는 메시지 로드 → URL 방 일치 → 작성자 → 멤버십이다.

```java
    @Transactional
    public Message update(Long chatroomId, Long messageId, Long memberId, String content) {
        Message message = getMessageById(messageId); // MESSAGE_NOT_FOUND on miss
        requireSameRoom(message, chatroomId);
        if (!message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        requireMember(memberId, message);
        if (message.isDeleted()) {
            throw new CustomException(ErrorCode.MESSAGE_NOT_FOUND); // 삭제된 메시지는 수정 불가
        }
        if (content == null || content.isBlank()) {
            throw new CustomException(ErrorCode.EMPTY_MESSAGE);
        }
        message.edit(content); // 더티체킹으로 반영
        return message;
    }

    @Transactional
    public Message delete(Long chatroomId, Long messageId, Long memberId) {
        Message message = getMessageById(messageId);
        requireSameRoom(message, chatroomId);
        if (!message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        requireMember(memberId, message);
        String imageUrl = message.getImageUrl();        // softDelete가 참조를 지우기 전에 읽는다
        message.softDelete();

        if (imageUrl != null && !imageUrl.isBlank()) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(imageUrl));
        }
        return message;
    }

    /** 전파 목적지는 엔티티의 방이므로 인가도 엔티티의 방을 기준으로 한다. */
    private void requireSameRoom(Message message, Long chatroomId) {
        if (!message.getChatRoom().getId().equals(chatroomId)) {
            throw new CustomException(ErrorCode.MESSAGE_NOT_FOUND);
        }
    }

    private void requireMember(Long memberId, Message message) {
        if (!roomAccess.isMember(memberId, message.getChatRoom().getId())) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
    }
```

- [ ] **Step 4: 컨트롤러가 방 id를 넘기게 한다**

`MessageController.java`의 두 호출을 바꾼다.

```java
        Message message = messageService.update(chatroomId, messageId, customUserDetails.getMemberId(), request.getContent());
```

```java
        Message message = messageService.delete(chatroomId, messageId, customUserDetails.getMemberId());
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*MessageAuthorizationTest*'
```

기대: PASS — 7 tests

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. 기존 테스트가 `update`/`delete`를 옛 시그니처로 부르고 있으면 방 id를 넘기도록 고친다(호출자가 그 방 멤버인 상황이어야 한다).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/MessageService.java src/main/java/com/example/springboot_realtimechat/controller/MessageController.java src/test/java/com/example/springboot_realtimechat/service/MessageAuthorizationTest.java
git commit -m "feat(authz): 메시지 수정·삭제에 방 일치와 멤버십 요구"
```

---

### Task 4: 멤버십 판정을 RoomAccess로 통일

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MessageService.java`

**Interfaces:**
- Consumes: `RoomAccess#isMember` (Task 3에서 이미 주입돼 있다)
- Produces: 없음. 외부 동작이 바뀌지 않는다

**배경:** `create`와 `getMessages`가 각자 `chatRoomMemberRepository.existsByMemberAndChatRoom`을 직접 부른다. 설계 §5-3이 약속한 "방 멤버인지 판단하는 코드가 레포에 하나"를 지키려면 이것도 `RoomAccess`를 지나야 한다.

**이 태스크는 동작을 바꾸지 않는다.** 판정 결과가 같으므로 기존 테스트가 그대로 통과해야 한다.

- [ ] **Step 1: `create`의 검사 교체**

`MessageService.create`에서 아래 부분을

```java
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);

        boolean exists = chatRoomMemberRepository.existsByMemberAndChatRoom(member, chatRoom);
        if(!exists){
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
```

이렇게 바꾼다.

```java
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);

        if (!roomAccess.isMember(memberId, chatroomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
```

- [ ] **Step 2: `getMessages`의 검사 교체**

`MessageService.getMessages`에서 아래 부분을

```java
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);
        if (!chatRoomMemberRepository.existsByMemberAndChatRoom(member, chatRoom)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
```

이렇게 바꾼다. `member` 지역변수는 이 메서드의 나머지에서 쓰이지 않으므로 함께 지운다.

```java
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);
        if (!roomAccess.isMember(memberId, chatroomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
```

> `member`가 아래에서 쓰이면 지우지 말고 그대로 둔다. 컴파일러가 알려준다.

- [ ] **Step 3: 남은 직접 호출이 없는지 확인**

```bash
grep -rn "existsByMemberAndChatRoom" src/main/java
```

기대: 출력 없음. 남아 있으면 그 자리도 `roomAccess.isMember`로 바꾼다. 리포지토리 메서드 자체는 지우지 않는다(범위 밖).

- [ ] **Step 4: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. 이 태스크는 리팩터링이므로 기존 테스트가 그대로 통과해야 한다. 깨지면 판정이 달라진 것이므로 원인을 찾는다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/MessageService.java
git commit -m "refactor(authz): 메시지 서비스의 멤버십 판정을 RoomAccess로 통일"
```

---

### Task 5: 타인 조회 응답에서 이메일 분리

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/dto/PublicMemberResponse.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/MemberController.java`
- Modify: `frontend/src/lib/api.ts`
- Test: `src/test/java/com/example/springboot_realtimechat/dto/PublicMemberResponseTest.java` (신규)

**Interfaces:**
- Consumes: `Member` 엔티티
- Produces: `PublicMemberResponse(Long id, String nickname, String profileImageUrl, LocalDateTime createdAt)` + `PublicMemberResponse.from(Member)`

**배경:** `GET /api/members/{id}`가 `/me`와 같은 DTO를 쓴다. id가 순차값이라 1..N 열거로 전 회원 email을 모을 수 있다. `/me`는 자기 이메일이므로 그대로 둔다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/dto/PublicMemberResponseTest.java`:

```java
package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.Member;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 타인 조회 응답에 이메일이 실리지 않는 것을 직렬화 결과로 고정한다. */
class PublicMemberResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 직렬화_결과에_이메일이_없다() throws Exception {
        Member member = new Member("secret@test.com", "1234", "닉");

        String json = objectMapper.writeValueAsString(PublicMemberResponse.from(member));

        assertThat(json).doesNotContain("secret@test.com");
        assertThat(json).doesNotContain("email");
        assertThat(json).contains("닉");
    }
}
```

`Member(String email, String password, String nickname)`는 실제 존재하는 생성자다. `createdAt`은 `@CreationTimestamp`라 영속화 전에는 `null`이고, 직렬화 결과에 `"createdAt":null`로 나가므로 `JavaTimeModule` 없이도 통과한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*PublicMemberResponseTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class PublicMemberResponse`

- [ ] **Step 3: 공개 DTO 생성**

`src/main/java/com/example/springboot_realtimechat/dto/PublicMemberResponse.java`:

```java
package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.Member;

import java.time.LocalDateTime;

/** 타인 조회용 회원 응답. 이메일을 싣지 않는다. */
public record PublicMemberResponse(
        Long id,
        String nickname,
        String profileImageUrl,
        LocalDateTime createdAt) {

    public static PublicMemberResponse from(Member member) {
        return new PublicMemberResponse(
                member.getId(),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getCreatedAt());
    }
}
```

- [ ] **Step 4: 컨트롤러가 공개 DTO를 반환하게 한다**

`MemberController.java`의 단건 조회를 아래로 바꾼다.

```java
    @GetMapping("/{id}")
    public PublicMemberResponse getMemberById(@PathVariable Long id){
        Member member = memberService.getMemberById(id);
        return PublicMemberResponse.from(member);
    }
```

import를 추가한다.

```java
import com.example.springboot_realtimechat.dto.PublicMemberResponse;
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*PublicMemberResponseTest*'
```

기대: PASS — 1 test

- [ ] **Step 6: 프론트 반환 타입 분리**

`frontend/src/lib/api.ts`에 공개 회원 타입을 추가한다(`BackendMember` 정의 아래).

```ts
/** 타인 조회 응답. 이메일이 없다. */
export interface BackendPublicMember {
  id: number;
  nickname: string;
  profileImageUrl?: string | null;
  createdAt?: string;
}
```

`getMemberById`의 반환 타입을 바꾼다.

```ts
export async function getMemberById(token: string, id: string) {
  return request<BackendPublicMember>(`/api/members/${id}`, {}, token);
}
```

`frontend/src/components/ProfileModal.tsx`의 import와 상태 타입을 바꾼다.

```tsx
import { getMemberById, BackendPublicMember } from '../lib/api';
```

```tsx
  const [member, setMember] = useState<BackendPublicMember | null>(null);
```

- [ ] **Step 7: 프론트 검증**

```bash
cd frontend && npm run lint && npm test && npm run build
```

기대: 세 명령 모두 종료 코드 0. 타입 오류가 나면 `ProfileModal`이 쓰는 필드가 `BackendPublicMember`에 다 있는지 확인한다(`id`·`nickname`·`profileImageUrl`·`createdAt`).

- [ ] **Step 8: 전체 백엔드 테스트**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/dto/PublicMemberResponse.java src/main/java/com/example/springboot_realtimechat/controller/MemberController.java src/test/java/com/example/springboot_realtimechat/dto/PublicMemberResponseTest.java frontend/src/lib/api.ts frontend/src/components/ProfileModal.tsx
git commit -m "feat(api): 타인 조회 응답에서 이메일 분리"
```

---

### Task 6: 최종 검증과 PR

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

- [ ] **Step 4: 멤버십 판정이 한 곳인지 확인**

```bash
grep -rn "existsByMemberAndChatRoom\|existsByMemberIdAndChatRoomId" src/main/java
```

기대: `RoomAccess.java`의 한 줄과 리포지토리 선언만 남는다. 서비스에서 직접 부르는 곳이 있으면 Task 4로 돌아간다.

- [ ] **Step 5: 설계 §7 자동 테스트 목록과 대조**

| §7 항목 | 테스트 |
|---|---|
| 비멤버가 방 참여자 목록을 조회하면 거부 | `RoomRosterAuthorizationTest.비멤버는_참여자_목록을_보지_못한다` |
| 멤버는 조회된다 | `RoomRosterAuthorizationTest.멤버는_참여자_목록을_본다` |
| 나간 회원이 자기 메시지를 수정·삭제하지 못한다 | `MessageAuthorizationTest.방을_나가면_자기_메시지도_수정하지_못한다` / `..._삭제하지_못한다` |
| 자기 방 id를 URL에 넣어도 다른 방 메시지에 손대지 못한다 | `MessageAuthorizationTest.자기가_속한_방_id를_붙여도_다른_방_메시지는_수정하지_못한다` |
| 존재하지 않는 방 id도 거부 | `MessageAuthorizationTest.존재하지_않는_방_id를_붙여도_거부된다` |
| 작성자가 아니면 `NOT_MESSAGE_OWNER` | `MessageAuthorizationTest.작성자가_아닌_멤버는_수정하지_못한다` |
| 타인 조회 응답에 email이 없다 | `PublicMemberResponseTest.직렬화_결과에_이메일이_없다` |
| 삭제한 엔드포인트가 매핑되지 않는다 | `DeletedEndpointsTest` 3건 |
| `create`·`getMessages` 회귀 | `MessageServiceTest` 기존 테스트 |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 6: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓴다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** 메시지 인가의 판정 축이 URL의 방이 아니라 엔티티의 방이라는 점이다. URL 기준으로 바꾸면 공격자가 자기 방 id를 넣어 통과하고 실제 쓰기·전파는 원래 방으로 가는 가짜 게이트가 된다.

```bash
git push -u origin feat/rest-authorization
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 7: 배포 후 실측 항목을 PR에 남긴다**

- 방에 입장한 상태에서 참여자 목록과 프로필 모달이 정상 동작하는지
- 방을 나간 뒤(API 직접 호출) 그 방의 자기 메시지 수정이 거부되는지
- 로그인·온보딩·프로필 수정 화면이 그대로 동작하는지(`/me` 응답을 쓰는 경로)
- 메시지 전송·목록·수정·삭제가 정상 동작하는지(`RoomAccess` 교체 회귀)

---

## Self-Review

**스펙 커버리지 (설계 §2·§3·§7):**

| 요구 | 태스크 |
|---|---|
| R1 `GET /api/members` 삭제 | Task 1 |
| R2 타인 조회 email 분리 | Task 5 |
| R3 방 참여자 목록 멤버십 | Task 2 |
| R4 메시지 수정·삭제 멤버십 + 방 일치 | Task 3 |
| R5 `GET /api/chatrooms/{id}` 삭제 | Task 1 |
| R6 `getAllChatRoomMessages` 삭제 | Task 1 |
| D2 `RoomAccess` 단일화 | Task 2·3(신규 검사), Task 4(기존 검사 교체) |
| D3 엔티티 방 기준 + 404 | Task 3 |
| D4 공개 DTO + `/me` 유지 | Task 5 |
| §7 자동 테스트 | Task 1·2·3·5, Task 6 Step 5에서 대조 |
| §7 프론트 검증 | Task 5 Step 7, Task 6 Step 2 |
