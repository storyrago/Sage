# S3 태깅 계약 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** orphan 태깅의 근거를 호출자가 준 URL 문자열에서 DB의 잔여 참조로 바꾼다.

**Architecture:** `ImageReferences.isReferenced(url)`를 참조 판단의 유일한 지점으로 만들고, 프로필 교체·메시지 삭제·회원 탈퇴가 모두 합류하는 `ImageCleanupListener`에서 태깅 직전에 묻는다. 참조가 남아 있으면 태깅하지 않는다. `S3Service`는 손대지 않는다 — 우리 버킷 판별은 그대로 그 자리의 책임이다.

**Tech Stack:** Spring Boot 4.0.5, Spring Data JPA, JUnit 5, Mockito, H2, AWS SDK v2

## Global Constraints

- 설계 문서: `docs/superpowers/specs/2026-08-01-s3-tagging-contract-design.md`
- **참조 판단은 `ImageReferences.isReferenced(String url)` 하나만 쓴다.** 리스너 밖에서 참조를 세지 않는다
- **검사는 `ImageCleanupListener`에 둔다.** 발행자(서비스) 쪽에 넣지 않는다 — 세 번째 발행자가 생기면 그쪽이 검사를 빠뜨린다
- **`S3Service`를 수정하지 않는다.** 기존 `S3OrphanTaggingTest`가 그대로 통과해야 한다
- **판단할 수 없으면 태깅하지 않는다.** 참조 질의가 실패하면 경고를 남기고 그 URL을 건너뛴다
- 새 이벤트 타입을 만들지 않는다. `ImageDereferencedEvent(String url)` 하나를 계속 쓴다
- 이벤트 발행 조건(선행 설계 D4: 같은 URL 재저장이면 미발행)을 바꾸지 않는다. `ImageDereferenceEventTest`가 그대로 통과해야 한다
- 스키마 변경 없음. Flyway 마이그레이션을 추가하지 않는다. **인덱스도 추가하지 않는다**(설계 §6)
- 새 의존성 없음
- 백엔드 검증: `./gradlew test`. 프론트는 이 PR에서 바뀌지 않는다
- 브랜치: develop에서 `feat/s3-tagging-contract`을 새로 딴다. PR 대상은 **develop**
- 커밋 메시지·주석은 변경의 목적만 쓴다. 배경 서사를 넣지 않는다

## File Structure

| 파일 | 변경 |
|---|---|
| `service/ImageReferences.java` (신규) | `isReferenced(String url)` — 참조 여부 판단의 유일한 지점 |
| `repository/MemberRepository.java` (수정) | `existsByProfileImageUrl` |
| `repository/MessageRepository.java` (수정) | `existsByImageUrl`, 탈퇴용 `findImageUrlsByMember` |
| `event/ImageCleanupListener.java` (수정) | 태깅 전 참조 확인 |
| `service/MemberService.java` (수정) | `delete()`에서 프로필·메시지 이미지 URL마다 이벤트 발행 |

---

### Task 1: 참조 판단 컴포넌트

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/service/ImageReferences.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MemberRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java`
- Test: `src/test/java/com/example/springboot_realtimechat/s3/ImageReferencesTest.java` (신규)

**Interfaces:**
- Consumes: `MemberRepository`, `MessageRepository`
- Produces: `ImageReferences#isReferenced(String url): boolean` — Task 2의 리스너가 이것을 호출한다

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && git checkout develop && git pull && git checkout -b feat/s3-tagging-contract
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/s3/ImageReferencesTest.java`:

```java
package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.ImageReferences;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// 태깅 판단의 근거는 호출자가 준 URL이 아니라 DB의 잔여 참조다.
@SpringBootTest
@Transactional
class ImageReferencesTest {

    @Autowired ImageReferences imageReferences;
    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @MockitoBean S3Service s3Service;   // 실제 S3를 부르지 않는다

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/photo.png";

    @Test
    void 프로필이_참조하면_참조된_것이다() {
        Member member = memberService.create("ref1@e.com", "1234", "ref1");
        memberService.updateProfileImage(member.getId(), URL);

        assertThat(imageReferences.isReferenced(URL)).isTrue();
    }

    @Test
    void 메시지가_참조하면_참조된_것이다() {
        Member member = memberService.create("ref2@e.com", "1234", "ref2");
        ChatRoom room = chatRoomService.create("이미지방");
        chatRoomMemberService.join(member.getId(), room.getId());
        messageService.create(null, URL, member.getId(), room.getId(), null);

        assertThat(imageReferences.isReferenced(URL)).isTrue();
    }

    @Test
    void 아무도_참조하지_않으면_참조되지_않은_것이다() {
        assertThat(imageReferences.isReferenced(URL)).isFalse();
    }

    @Test
    void 소프트_삭제된_메시지는_참조로_세지_않는다() {
        Member member = memberService.create("ref3@e.com", "1234", "ref3");
        ChatRoom room = chatRoomService.create("삭제방");
        chatRoomMemberService.join(member.getId(), room.getId());
        Message message = messageService.create(null, URL, member.getId(), room.getId(), null);

        messageService.delete(room.getId(), message.getId(), member.getId());

        assertThat(imageReferences.isReferenced(URL)).isFalse();
    }

    @Test
    void null과_빈_문자열은_참조되지_않은_것으로_본다() {
        assertThat(imageReferences.isReferenced(null)).isFalse();
        assertThat(imageReferences.isReferenced("  ")).isFalse();
    }
}
```

> `MessageService.create`의 시그니처는 `create(String content, String imageUrl, Long memberId, Long chatroomId, Long replyToId)`이고, `delete`는 `delete(Long chatroomId, Long messageId, Long memberId)`다.

- [ ] **Step 3: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*ImageReferencesTest*'
```

기대: 컴파일 실패 — `cannot find symbol: class ImageReferences`

- [ ] **Step 4: 리포지토리에 조회 추가**

`MemberRepository.java`에 추가한다.

```java
    boolean existsByProfileImageUrl(String profileImageUrl);
```

`MessageRepository.java`에 추가한다.

```java
    boolean existsByImageUrl(String imageUrl);
```

- [ ] **Step 5: 참조 판단 컴포넌트 작성**

`src/main/java/com/example/springboot_realtimechat/service/ImageReferences.java`:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이 이미지 URL을 아직 참조하는 행이 있는지 판단하는 유일한 지점.
 * 태깅 판단의 근거를 호출자가 준 문자열이 아니라 DB 상태로 만든다.
 */
@Component
@RequiredArgsConstructor
public class ImageReferences {

    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;

    @Transactional(readOnly = true)
    public boolean isReferenced(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return memberRepository.existsByProfileImageUrl(url)
                || messageRepository.existsByImageUrl(url);
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew test --tests '*ImageReferencesTest*'
```

기대: PASS — 5 tests

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/ImageReferences.java src/main/java/com/example/springboot_realtimechat/repository/MemberRepository.java src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java src/test/java/com/example/springboot_realtimechat/s3/ImageReferencesTest.java
git commit -m "feat(s3): 이미지 URL의 잔여 참조를 판단하는 컴포넌트 추가"
```

---

### Task 2: 태깅 전 참조 확인

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/event/ImageCleanupListener.java`
- Test: `src/test/java/com/example/springboot_realtimechat/s3/ImageReferenceGateTest.java` (신규)

**Interfaces:**
- Consumes: `ImageReferences#isReferenced(String url): boolean` (Task 1)
- Produces: 없음. 리스너의 외부 시그니처는 그대로다

**배경:** 프로필 교체·메시지 삭제가 `ImageDereferencedEvent` 하나로 합류하는 지점이 리스너다. 여기에 검사를 두면 발행자가 늘어나도 자동으로 적용된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/s3/ImageReferenceGateTest.java`:

```java
package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.ImageCleanupListener;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 살아있는 객체에 만료 태그가 붙으면 안 된다. 판단은 DB의 잔여 참조로만 한다.
@SpringBootTest
@Transactional
class ImageReferenceGateTest {

    @Autowired ImageCleanupListener listener;
    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @MockitoBean S3Service s3Service;

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/victim.png";

    @Test
    void 아무도_참조하지_않으면_태깅한다() {
        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service).tagAsOrphan(URL);
    }

    @Test
    void 다른_회원이_프로필로_참조하면_태깅하지_않는다() {
        Member victim = memberService.create("gate-victim@e.com", "1234", "피해자");
        memberService.updateProfileImage(victim.getId(), URL);

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 다른_메시지가_참조하면_태깅하지_않는다() {
        Member owner = memberService.create("gate-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("게이트방");
        chatRoomMemberService.join(owner.getId(), room.getId());
        messageService.create(null, URL, owner.getId(), room.getId(), null);

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 공격_재현_남의_URL을_프로필에_넣었다_바꿔도_피해자_객체는_태깅되지_않는다() {
        Member victim = memberService.create("atk-victim@e.com", "1234", "피해자");
        memberService.updateProfileImage(victim.getId(), URL);   // 피해자가 참조 중

        Member attacker = memberService.create("atk-attacker@e.com", "1234", "공격자");
        memberService.updateProfileImage(attacker.getId(), URL); // 남의 URL을 자기 프로필로
        memberService.updateProfileImage(attacker.getId(),
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/other.png"); // 참조 해제 이벤트 유발

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 공격_재현_남의_URL을_메시지에_붙였다_지워도_피해자_객체는_태깅되지_않는다() {
        Member victim = memberService.create("atk2-victim@e.com", "1234", "피해자2");
        memberService.updateProfileImage(victim.getId(), URL);

        Member attacker = memberService.create("atk2-attacker@e.com", "1234", "공격자2");
        ChatRoom room = chatRoomService.create("공격방");
        chatRoomMemberService.join(attacker.getId(), room.getId());
        var message = messageService.create(null, URL, attacker.getId(), room.getId(), null);
        messageService.delete(room.getId(), message.getId(), attacker.getId());

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*ImageReferenceGateTest*'
```

기대: `아무도_참조하지_않으면_태깅한다`만 PASS. 나머지 4건은 FAIL — 지금은 참조 여부와 무관하게 태깅한다.

- [ ] **Step 3: 리스너에 참조 확인 추가**

`ImageCleanupListener.java` 전체를 아래로 바꾼다.

```java
package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.service.ImageReferences;
import com.example.springboot_realtimechat.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupListener {

    private final S3Service s3Service;
    private final ImageReferences imageReferences;

    // 커밋된 뒤에만 태깅한다. 트랜잭션 안에서 태깅하면 롤백되어도 태그가 S3에 남아,
    // 여전히 사용 중인 객체가 수명주기 규칙으로 만료된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onImageDereferenced(ImageDereferencedEvent event) {
        try {
            // 다른 행이 아직 이 URL을 참조하면 살아있는 객체다.
            // 판단할 수 없을 때(질의 실패)도 태깅하지 않는 쪽이 안전하다.
            if (imageReferences.isReferenced(event.url())) {
                return;
            }
            s3Service.tagAsOrphan(event.url());
        } catch (Exception e) {
            log.warn("이미지 orphan 태깅 실패: url={}", event.url(), e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests '*ImageReferenceGateTest*'
```

기대: PASS — 5 tests

- [ ] **Step 5: 게이트가 실제로 동작하는지 뮤테이션으로 확인**

`ImageCleanupListener`의 `if (imageReferences.isReferenced(event.url())) { return; }` 세 줄을 잠시 주석 처리하고 다시 실행한다.

```bash
./gradlew test --tests '*ImageReferenceGateTest*'
```

기대: **4건 FAIL**. 전부 통과하면 이 테스트가 아무것도 검증하지 않는 것이므로 원인을 찾는다. 확인 후 주석을 되돌린다.

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL. 기존 `ImageCleanupListenerTest`는 DB에 그 URL을 참조하는 행이 없으므로 그대로 통과한다. 깨지면 참조 판단이 잘못된 것이므로 테스트를 고치지 말고 원인을 찾는다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/event/ImageCleanupListener.java src/test/java/com/example/springboot_realtimechat/s3/ImageReferenceGateTest.java
git commit -m "fix(s3): 잔여 참조가 있으면 orphan 태깅을 건너뜀"
```

---

### Task 3: 회원 탈퇴를 태깅 경로에 연결

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java`
- Test: `src/test/java/com/example/springboot_realtimechat/s3/MemberDeleteImageCleanupTest.java` (신규)

**Interfaces:**
- Consumes: `ImageDereferencedEvent(String url)` (기존), Task 2의 리스너 게이트
- Produces: `MessageRepository#findImageUrlsByMember(Member member): List<String>`

**배경:** 지금 `MemberService.delete`는 메시지를 벌크 삭제하고 회원을 지우면서 참조 해제 이벤트를 하나도 발행하지 않는다. 탈퇴자의 프로필 사진과 이미지 메시지는 버킷에 영원히 남는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/springboot_realtimechat/s3/MemberDeleteImageCleanupTest.java`:

```java
package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴자의 이미지가 버킷에 영원히 남지 않게 한다. 실제 태깅 여부는 리스너의 참조 검사가 정한다.
@SpringBootTest
@Transactional
@RecordApplicationEvents
class MemberDeleteImageCleanupTest {

    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ApplicationEvents events;

    @MockitoBean S3Service s3Service;

    private static final String PROFILE = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/profile.png";
    private static final String IMAGE_A = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/a.png";
    private static final String IMAGE_B = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/b.png";

    private List<String> publishedUrls() {
        return events.stream(ImageDereferencedEvent.class).map(ImageDereferencedEvent::url).toList();
    }

    @Test
    void 탈퇴하면_프로필과_이미지_메시지_URL마다_이벤트가_발행된다() {
        Member member = memberService.create("del1@e.com", "1234", "탈퇴자");
        memberService.updateProfileImage(member.getId(), PROFILE);
        ChatRoom room = chatRoomService.create("탈퇴방");
        chatRoomMemberService.join(member.getId(), room.getId());
        messageService.create(null, IMAGE_A, member.getId(), room.getId(), null);
        messageService.create(null, IMAGE_B, member.getId(), room.getId(), null);

        memberService.delete(member.getId());

        assertThat(publishedUrls()).contains(PROFILE, IMAGE_A, IMAGE_B);
    }

    @Test
    void 같은_URL을_여러_메시지가_쓰면_한_번만_발행된다() {
        Member member = memberService.create("del2@e.com", "1234", "탈퇴자2");
        ChatRoom room = chatRoomService.create("중복방");
        chatRoomMemberService.join(member.getId(), room.getId());
        messageService.create(null, IMAGE_A, member.getId(), room.getId(), null);
        messageService.create(null, IMAGE_A, member.getId(), room.getId(), null);

        memberService.delete(member.getId());

        assertThat(publishedUrls()).filteredOn(url -> url.equals(IMAGE_A)).hasSize(1);
    }

    @Test
    void 이미지가_없는_회원의_탈퇴는_이벤트를_발행하지_않는다() {
        Member member = memberService.create("del3@e.com", "1234", "탈퇴자3");

        memberService.delete(member.getId());

        assertThat(publishedUrls()).isEmpty();
    }
}
```

> 프로필 교체가 발행하는 이벤트와 섞이지 않도록, 프로필을 처음 설정할 때는 옛 URL이 없어 이벤트가 발행되지 않는다(선행 설계 D4). 첫 테스트의 `contains`는 그래서 정확히 동작한다.

- [ ] **Step 2: 테스트가 실패하는 것 확인**

```bash
./gradlew test --tests '*MemberDeleteImageCleanupTest*'
```

기대: 앞의 두 테스트가 FAIL(발행된 URL이 없다), 세 번째는 PASS.

- [ ] **Step 3: 리포지토리에 조회 추가**

`MessageRepository.java`에 추가한다.

```java
    @Query("SELECT m.imageUrl FROM Message m WHERE m.member = :member AND m.imageUrl IS NOT NULL")
    List<String> findImageUrlsByMember(@Param("member") Member member);
```

- [ ] **Step 4: 탈퇴 시 이벤트 발행**

`MemberService.delete`를 아래로 바꾼다.

```java
    @Transactional
    public void delete(Long id){
        Member member = memberRepository.findById(id)
                .orElseThrow(()-> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 삭제 후에는 조회할 수 없으므로 참조하던 이미지 URL을 먼저 모은다.
        Set<String> imageUrls = new LinkedHashSet<>(messageRepository.findImageUrlsByMember(member));
        String profileImageUrl = member.getProfileImageUrl();
        if (profileImageUrl != null && !profileImageUrl.isBlank()) {
            imageUrls.add(profileImageUrl);
        }

        chatRoomMemberRepository.deleteByMember(member);
        messageRepository.deleteByMember(member);
        memberRepository.delete(member);

        imageUrls.forEach(url -> eventPublisher.publishEvent(new ImageDereferencedEvent(url)));
        eventPublisher.publishEvent(new MemberDeletedEvent(id));
    }
```

import를 추가한다.

```java
import java.util.LinkedHashSet;
import java.util.Set;
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests '*MemberDeleteImageCleanupTest*'
```

기대: PASS — 3 tests

- [ ] **Step 6: 전체 테스트**

```bash
./gradlew test
```

기대: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/springboot_realtimechat/repository/MessageRepository.java src/main/java/com/example/springboot_realtimechat/service/MemberService.java src/test/java/com/example/springboot_realtimechat/s3/MemberDeleteImageCleanupTest.java
git commit -m "feat(s3): 회원 탈퇴 시 참조하던 이미지를 정리 대상으로 발행"
```

---

### Task 4: 최종 검증과 PR

**Files:** 없음 (검증만)

- [ ] **Step 1: 백엔드 전체 테스트**

```bash
cd /Users/cheonjamin/projects/realtimechat-backend && ./gradlew test --rerun-tasks
```

기대: BUILD SUCCESSFUL. `--rerun-tasks`를 쓰는 이유는 `UP-TO-DATE` 캐시가 실행을 건너뛰면 검증이 아니기 때문이다.

- [ ] **Step 2: 기존 계약이 그대로인지 확인**

```bash
./gradlew test --tests '*S3OrphanTaggingTest*' --tests '*ImageDereferenceEventTest*' --tests '*ImageCleanupListenerTest*'
```

기대: 전부 PASS. 이 PR은 `S3Service`와 이벤트 발행 조건을 바꾸지 않는다.

- [ ] **Step 3: 스키마 변경이 없는지 확인**

```bash
git diff develop --stat -- 'src/main/resources/db/migration'
```

기대: 출력 없음

- [ ] **Step 4: 참조 판단이 한 곳인지 확인**

```bash
grep -rn "existsByProfileImageUrl\|existsByImageUrl" src/main/java
```

기대: 리포지토리 선언 2줄과 `ImageReferences` 안의 호출 2줄만 나온다. 다른 곳에서 직접 부르면 그 자리를 `ImageReferences`로 바꾼다.

- [ ] **Step 5: 설계 §7 자동 테스트 목록과 대조**

| §7 항목 | 테스트 |
|---|---|
| 다른 회원이 프로필로 참조하면 태깅하지 않는다 | `ImageReferenceGateTest.다른_회원이_프로필로_참조하면_태깅하지_않는다` |
| 다른 메시지가 참조하면 태깅하지 않는다 | `ImageReferenceGateTest.다른_메시지가_참조하면_태깅하지_않는다` |
| 아무도 참조하지 않으면 태깅한다 | `ImageReferenceGateTest.아무도_참조하지_않으면_태깅한다` |
| 공격 재현(프로필) | `ImageReferenceGateTest.공격_재현_남의_URL을_프로필에_넣었다_바꿔도_피해자_객체는_태깅되지_않는다` |
| 공격 재현(메시지) | `ImageReferenceGateTest.공격_재현_남의_URL을_메시지에_붙였다_지워도_피해자_객체는_태깅되지_않는다` |
| 소프트 삭제된 메시지는 참조로 세지 않는다 | `ImageReferencesTest.소프트_삭제된_메시지는_참조로_세지_않는다` |
| 탈퇴 시 URL마다 이벤트 발행 | `MemberDeleteImageCleanupTest.탈퇴하면_프로필과_이미지_메시지_URL마다_이벤트가_발행된다` |
| 탈퇴자의 URL을 남이 참조 중이면 태깅되지 않는다 | `ImageReferenceGateTest.다른_회원이_프로필로_참조하면_태깅하지_않는다`(같은 게이트를 지난다) |
| 질의 실패 시 태깅하지 않고 예외를 내보내지 않는다 | `ImageCleanupListenerTest.태깅이_실패해도_예외가_전파되지_않는다`(같은 try 블록) |

빠진 항목이 있으면 해당 태스크로 돌아가 테스트를 추가한다.

- [ ] **Step 6: PR 생성**

본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음"이라고 적는다. `## 검증`에는 실제로 실행한 것만 쓴다.

**`## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다.** 참조 검사를 리스너가 아니라 발행자(서비스) 쪽으로 옮기면, 새 발행자가 생겼을 때 그쪽이 검사를 빠뜨려 계약이 다시 뚫린다.

```bash
git push -u origin feat/s3-tagging-contract
```

PR 대상 브랜치는 **develop**이다. 머지는 사용자가 한다.

- [ ] **Step 7: 배포 후 실측 항목을 PR에 남긴다**

- 프로필 사진을 두 번 바꾸고 옛 사진이 다른 곳에서 안 쓰이면 S3 콘솔에서 그 객체에 `orphan=true` 태그가 붙는지
- 같은 사진을 두 계정이 프로필로 쓰는 상태에서 한쪽이 바꾸면 태그가 붙지 않는지
- 이미지 메시지를 보내고 지우면 태그가 붙는지
- 탈퇴 후 그 회원의 이미지에 태그가 붙는지

---

## Self-Review

**스펙 커버리지 (설계 §2·§3·§7):**

| 요구 | 태스크 |
|---|---|
| D1 태깅 직전 잔여 참조 질의 | Task 1(판단), Task 2(적용) |
| D2 검사는 리스너에 둔다 | Task 2 |
| D3 판단은 `ImageReferences` 한 곳 | Task 1, Task 4 Step 4에서 확인 |
| D4 회원 탈퇴 연결 | Task 3 |
| D5 판단 실패 시 태깅하지 않음 | Task 2(같은 try 블록) |
| `S3Service` 미수정 | Global Constraints, Task 4 Step 2에서 확인 |
| §7 자동 테스트 | Task 1·2·3, Task 4 Step 5에서 대조 |
| §7 배포 후 실측 | Task 4 Step 7 |
