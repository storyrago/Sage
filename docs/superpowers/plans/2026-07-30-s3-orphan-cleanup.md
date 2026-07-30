# S3 고아 객체 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 참조가 끊긴 S3 객체에 `orphan=true` 태그를 달아 버킷 수명주기 규칙이 만료시킬 수 있게 한다.

**Architecture:** 프로필 사진 교체와 이미지 메시지 삭제가 `ImageDereferencedEvent`를 발행하고, `@TransactionalEventListener(AFTER_COMMIT)`가 커밋된 뒤에만 `S3Service.tagAsOrphan`을 호출한다. 태깅 실패는 로그만 남기고 본 작업을 실패시키지 않는다. 삭제는 코드가 하지 않고 S3 수명주기 규칙이 한다.

**Tech Stack:** Spring Boot 4 / AWS SDK v2 (`S3Client`) / JUnit5 + Mockito(`@MockitoBean`) + `@RecordApplicationEvents`

설계 문서: `docs/superpowers/specs/2026-07-30-s3-orphan-cleanup-design.md`

## Global Constraints

- 브랜치는 `feat/s3-orphan-cleanup`(이미 존재, `origin/develop`에서 분기). PR 대상은 **develop**.
- **프론트(`frontend/`)를 건드리지 않는다. 스키마 변경도 없다** — Flyway 마이그레이션 파일을 추가하지 않는다.
- 코드가 S3 객체를 **삭제하지 않는다.** 태깅만 한다. `deleteObject` 계열 API를 호출하지 마라.
- 태그는 키 `orphan`, 값 `true` 고정.
- 태깅 실패는 예외를 밖으로 던지지 않는다. 로그만 남긴다.
- 우리 버킷 URL(`https://{bucket}.s3.{region}.amazonaws.com/`로 시작)이 아니면 아무것도 하지 않는다.
- 검증 명령은 `./gradlew test`. 테스트는 **Redis(localhost:6379)와 MySQL이 떠 있어야** 통과한다(기존 테스트가 이미 그렇다).
- 테스트 메서드 이름은 한글 관례를 따른다(예: `void 소셜회원_저장_후_provider와_providerId로_조회된다()`).
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- 테스트 설정(`src/test/resources/application.yaml`)의 값은 `aws.s3.bucket: test-bucket`, `aws.s3.region: ap-northeast-2`다. 테스트 URL은 이 값으로 조립한다.

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `service/S3Service.java` | `tagAsOrphan(String url)` — URL 검증·키 추출·태깅 | 수정 |
| `event/ImageDereferencedEvent.java` | 참조가 끊긴 이미지 URL을 나르는 이벤트 | 생성 |
| `event/ImageCleanupListener.java` | `AFTER_COMMIT`에서 태깅, 실패 로깅 | 생성 |
| `service/MemberService.java` | 프로필 교체 시 옛 URL로 이벤트 발행 | 수정 |
| `service/MessageService.java` | 메시지 삭제 시 붙어 있던 URL로 이벤트 발행 | 수정 |
| `s3/S3OrphanTaggingTest.java` | 태깅 대상 판별과 요청 내용 | 생성(테스트) |
| `s3/ImageCleanupListenerTest.java` | 리스너의 위임과 예외 삼킴 | 생성(테스트) |
| `s3/ImageDereferenceEventTest.java` | 두 서비스의 이벤트 발행 조건 | 생성(테스트) |

---

## Task 1: `S3Service.tagAsOrphan` (TDD)

**Files:**
- Create: `src/test/java/com/example/springboot_realtimechat/s3/S3OrphanTaggingTest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/S3Service.java`

**Interfaces:**
- Produces: `S3Service.tagAsOrphan(String url): void` — 우리 버킷 URL이면 `orphan=true` 태그를 달고, 아니면 아무것도 하지 않는다. Task 2의 리스너가 호출한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/s3/S3OrphanTaggingTest.java`를 만든다:

```java
package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 참조가 끊긴 객체만 태깅해야 한다. 남의 URL이나 살아있는 객체에 태그가 붙으면
// 수명주기 규칙이 사용 중인 이미지를 만료시킨다.
@SpringBootTest
class S3OrphanTaggingTest {

    @Autowired
    S3Service s3Service;

    @MockitoBean
    S3Client s3Client;

    private static final String OURS = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/abc_photo.png";

    @Test
    void 우리_버킷_URL이면_orphan_태그를_단다() {
        s3Service.tagAsOrphan(OURS);

        ArgumentCaptor<PutObjectTaggingRequest> captor = ArgumentCaptor.forClass(PutObjectTaggingRequest.class);
        verify(s3Client).putObjectTagging(captor.capture());

        PutObjectTaggingRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo("abc_photo.png");
        assertThat(request.tagging().tagSet())
                .extracting(Tag::key, Tag::value)
                .containsExactly(tuple("orphan", "true"));
    }

    @Test
    void 외부_제공자_URL이면_태깅하지_않는다() {
        s3Service.tagAsOrphan("https://lh3.googleusercontent.com/a/photo.jpg");

        verifyNoInteractions(s3Client);
    }

    @Test
    void 값이_없으면_태깅하지_않는다() {
        s3Service.tagAsOrphan(null);
        s3Service.tagAsOrphan("");
        s3Service.tagAsOrphan("   ");

        verifyNoInteractions(s3Client);
    }

    @Test
    void 키가_비어있는_URL이면_태깅하지_않는다() {
        s3Service.tagAsOrphan("https://test-bucket.s3.ap-northeast-2.amazonaws.com/");

        verifyNoInteractions(s3Client);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*S3OrphanTaggingTest'`
Expected: FAIL — `tagAsOrphan` 메서드가 없어 컴파일되지 않는다.

- [ ] **Step 3: `tagAsOrphan`을 구현한다**

`S3Service.java`의 import에 추가한다:

```java
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
```

`upload` 메서드의 마지막 `return` 문을 아래로 바꿔 URL 접두사 조립을 한 곳으로 모은다:

```java
        // ④ 공개 URL 조립해서 반환
        return publicUrlPrefix() + key;
```

그리고 클래스 끝(마지막 `}` 앞)에 추가한다:

```java
    // 참조가 끊긴 객체에 orphan 태그를 단다. 삭제는 버킷 수명주기 규칙이 한다.
    public void tagAsOrphan(String url) {
        String key = extractKey(url);
        if (key == null) return;          // 우리 버킷 객체가 아니면 아무것도 하지 않는다

        s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(bucket)
                .key(key)
                .tagging(Tagging.builder()
                        .tagSet(Tag.builder().key("orphan").value("true").build())
                        .build())
                .build());
    }

    private String publicUrlPrefix() {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/";
    }

    // 우리 버킷 URL이면 키를, 아니면 null을 돌려준다.
    // 업로드가 URL을 문자열 연결로 조립하므로 역변환도 접두사 제거로 정확히 일치한다.
    private String extractKey(String url) {
        if (url == null || url.isBlank()) return null;

        String prefix = publicUrlPrefix();
        if (!url.startsWith(prefix)) return null;

        String key = url.substring(prefix.length());
        return key.isBlank() ? null : key;
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests '*S3OrphanTaggingTest'`
Expected: PASS (4개 테스트)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/S3Service.java src/test/java/com/example/springboot_realtimechat/s3/S3OrphanTaggingTest.java
git commit -m "feat(s3): 참조가 끊긴 객체에 orphan 태그를 다는 기능 추가"
```

---

## Task 2: 이벤트와 커밋 이후 리스너 (TDD)

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/event/ImageDereferencedEvent.java`
- Create: `src/main/java/com/example/springboot_realtimechat/event/ImageCleanupListener.java`
- Create: `src/test/java/com/example/springboot_realtimechat/s3/ImageCleanupListenerTest.java`

**Interfaces:**
- Consumes: Task 1의 `S3Service.tagAsOrphan(String url)`
- Produces:
  - `ImageDereferencedEvent(String url)` — record. Task 3의 두 서비스가 발행한다.
  - `ImageCleanupListener.onImageDereferenced(ImageDereferencedEvent event)` — `AFTER_COMMIT`에서 실행된다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/s3/ImageCleanupListenerTest.java`를 만든다:

```java
package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.event.ImageCleanupListener;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

// 정리는 부가 작업이다. 태깅이 실패해도 이미 커밋된 본 작업에 영향을 주면 안 된다.
@SpringBootTest
class ImageCleanupListenerTest {

    @Autowired
    ImageCleanupListener listener;

    @MockitoBean
    S3Service s3Service;

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/old_photo.png";

    @Test
    void 이벤트의_URL로_태깅을_요청한다() {
        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service).tagAsOrphan(URL);
    }

    @Test
    void 태깅이_실패해도_예외가_전파되지_않는다() {
        doThrow(new RuntimeException("S3 불통")).when(s3Service).tagAsOrphan(anyString());

        assertThatNoException()
                .isThrownBy(() -> listener.onImageDereferenced(new ImageDereferencedEvent(URL)));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*ImageCleanupListenerTest'`
Expected: FAIL — `ImageCleanupListener`, `ImageDereferencedEvent` 클래스가 없어 컴파일되지 않는다.

- [ ] **Step 3: 이벤트를 만든다**

`src/main/java/com/example/springboot_realtimechat/event/ImageDereferencedEvent.java`:

```java
package com.example.springboot_realtimechat.event;

// 참조가 끊긴 이미지 URL. 커밋 이후 정리 대상이 된다.
public record ImageDereferencedEvent(String url) {
}
```

- [ ] **Step 4: 리스너를 만든다**

`src/main/java/com/example/springboot_realtimechat/event/ImageCleanupListener.java`:

```java
package com.example.springboot_realtimechat.event;

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

    // 커밋된 뒤에만 태깅한다. 트랜잭션 안에서 태깅하면 롤백되어도 태그가 S3에 남아,
    // 여전히 사용 중인 객체가 수명주기 규칙으로 만료된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onImageDereferenced(ImageDereferencedEvent event) {
        try {
            s3Service.tagAsOrphan(event.url());
        } catch (Exception e) {
            log.warn("이미지 orphan 태깅 실패: url={}", event.url(), e);
        }
    }
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew test --tests '*ImageCleanupListenerTest'`
Expected: PASS (2개 테스트)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/event src/test/java/com/example/springboot_realtimechat/s3/ImageCleanupListenerTest.java
git commit -m "feat(s3): 커밋 이후 이미지 정리를 수행하는 이벤트와 리스너 추가"
```

---

## Task 3: 두 서비스에서 이벤트 발행 (TDD)

**Files:**
- Create: `src/test/java/com/example/springboot_realtimechat/s3/ImageDereferenceEventTest.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MemberService.java` (`updateProfileImage`)
- Modify: `src/main/java/com/example/springboot_realtimechat/service/MessageService.java` (`delete`)

**Interfaces:**
- Consumes: Task 2의 `ImageDereferencedEvent(String url)`
- Produces: 없음 (종단)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/s3/ImageDereferenceEventTest.java`를 만든다:

```java
package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
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
import static org.mockito.Mockito.verifyNoInteractions;

// 참조가 끊긴 경우에만 이벤트가 나가야 한다. 같은 URL로 다시 저장하는 경로에서
// 이벤트가 나가면 살아있는 사진에 만료 태그가 붙는다.
@SpringBootTest
@Transactional
@RecordApplicationEvents
class ImageDereferenceEventTest {

    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ApplicationEvents events;

    @MockitoBean S3Service s3Service;   // 커밋 전에는 호출되지 않아야 한다

    private static final String OLD = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/old_photo.png";
    private static final String NEW = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/new_photo.png";

    private List<String> publishedUrls() {
        return events.stream(ImageDereferencedEvent.class).map(ImageDereferencedEvent::url).toList();
    }

    @Test
    void 프로필_사진을_교체하면_옛_URL로_이벤트가_발행된다() {
        Member member = memberService.create("p1@e.com", "1234", "p1");
        memberService.updateProfileImage(member.getId(), OLD);

        memberService.updateProfileImage(member.getId(), NEW);

        assertThat(publishedUrls()).containsExactly(OLD);
    }

    @Test
    void 같은_URL로_다시_저장하면_이벤트가_발행되지_않는다() {
        Member member = memberService.create("p2@e.com", "1234", "p2");
        memberService.updateProfileImage(member.getId(), OLD);

        memberService.updateProfileImage(member.getId(), OLD);

        assertThat(publishedUrls()).isEmpty();
    }

    @Test
    void 사진이_없던_회원은_이벤트가_발행되지_않는다() {
        Member member = memberService.create("p3@e.com", "1234", "p3");

        memberService.updateProfileImage(member.getId(), NEW);

        assertThat(publishedUrls()).isEmpty();
    }

    @Test
    void 이미지_메시지를_삭제하면_그_URL로_이벤트가_발행된다() {
        Member author = memberService.create("m1@e.com", "1234", "m1");
        ChatRoom room = chatRoomService.create("정리방");
        chatRoomMemberService.join(author.getId(), room.getId());
        Message message = messageService.create(null, OLD, author.getId(), room.getId(), null);

        messageService.delete(message.getId(), author.getId());

        assertThat(publishedUrls()).containsExactly(OLD);
    }

    // 이 테스트 클래스는 @Transactional이라 커밋되지 않는다.
    // AFTER_COMMIT 리스너가 실행되지 않는다는 것이 곧 D2가 지켜진다는 증거다.
    @Test
    void 커밋되지_않으면_태깅이_일어나지_않는다() {
        Member member = memberService.create("p4@e.com", "1234", "p4");
        memberService.updateProfileImage(member.getId(), OLD);

        memberService.updateProfileImage(member.getId(), NEW);

        assertThat(publishedUrls()).containsExactly(OLD);   // 이벤트는 발행됐지만
        verifyNoInteractions(s3Service);                    // 커밋 전이므로 태깅은 없다
    }

    @Test
    void 이미지가_없는_메시지를_삭제하면_이벤트가_발행되지_않는다() {
        Member author = memberService.create("m2@e.com", "1234", "m2");
        ChatRoom room = chatRoomService.create("정리방2");
        chatRoomMemberService.join(author.getId(), room.getId());
        Message message = messageService.create("글만 있는 메시지", null, author.getId(), room.getId(), null);

        messageService.delete(message.getId(), author.getId());

        assertThat(publishedUrls()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*ImageDereferenceEventTest'`
Expected: FAIL — 이벤트를 발행하는 코드가 없어 `publishedUrls()`가 비어 있다(교체·메시지 삭제 케이스에서 실패).

- [ ] **Step 3: `MemberService`가 이벤트를 발행하게 한다**

`MemberService.java`의 import에 추가한다:

```java
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import org.springframework.context.ApplicationEventPublisher;
```

필드 목록(`private final MessageRepository messageRepository;` 아래)에 추가한다:

```java
    private final ApplicationEventPublisher eventPublisher;
```

`updateProfileImage`를 아래로 교체한다:

```java
    @Transactional
    public Member updateProfileImage(Long memberId, String imageUrl) {
        Member member = getMemberById(memberId);        // 기존 메서드 재사용
        String oldUrl = member.getProfileImageUrl();
        member.updateProfileImageUrl(imageUrl);         // 엔티티에 만든 메서드

        // 같은 URL로 다시 저장하는 경로(사진 확정 후 닉네임 저장 실패 → 재시도)에서
        // 이벤트가 나가면 살아있는 사진에 만료 태그가 붙는다.
        if (oldUrl != null && !oldUrl.isBlank() && !oldUrl.equals(imageUrl)) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(oldUrl));
        }
        return member;
    }
```

- [ ] **Step 4: `MessageService`가 이벤트를 발행하게 한다**

`MessageService.java`의 import에 추가한다:

```java
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import org.springframework.context.ApplicationEventPublisher;
```

필드 목록(`private final ChatRoomService chatRoomService;` 아래)에 추가한다:

```java
    private final ApplicationEventPublisher eventPublisher;
```

`delete`를 아래로 교체한다:

```java
    @Transactional
    public Message delete(Long messageId, Long memberId) {
        Message message = getMessageById(messageId);
        if (!message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        String imageUrl = message.getImageUrl();        // softDelete가 참조를 지우기 전에 읽는다
        message.softDelete();

        if (imageUrl != null && !imageUrl.isBlank()) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(imageUrl));
        }
        return message;
    }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew test --tests '*ImageDereferenceEventTest'`
Expected: PASS (6개 테스트)

Run: `./gradlew test`
Expected: 전체 통과. 통과 개수를 기록한다 — PR 본문에 쓴다.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/MemberService.java src/main/java/com/example/springboot_realtimechat/service/MessageService.java src/test/java/com/example/springboot_realtimechat/s3/ImageDereferenceEventTest.java
git commit -m "feat(s3): 프로필 교체·메시지 삭제 시 정리 이벤트 발행"
```

---

## Task 4: PR과 배포 후 확인

**Files:** 없음 (검증·문서 단계)

- [ ] **Step 1: 전체 테스트를 다시 돌린다**

Run: `./gradlew test`
Expected: 전체 통과. 통과 개수를 기록한다.

- [ ] **Step 2: 삭제 API를 쓰지 않았는지 확인한다**

Run: `grep -rn "deleteObject\|DeleteObject" src/main/java`
Expected: 출력 없음. 이 작업은 태깅만 한다.

- [ ] **Step 3: 프론트와 스키마가 그대로인지 확인한다**

Run: `git diff origin/develop --stat -- frontend/ src/main/resources/db/migration/`
Expected: 출력 없음.

- [ ] **Step 4: 브랜치를 푸시한다**

```bash
git push -u origin feat/s3-orphan-cleanup
```

- [ ] **Step 5: PR을 만든다**

`.github/pull_request_template.md`의 5개 섹션(개요 / 변경 내용 / 검증 / 배포 영향 / 구현 노트·알려진 한계)을 그대로 채운다. `## 리뷰어가 꼭 봐야 할 변경`을 `## 검증` 바로 앞에 추가한다 — **AWS 콘솔 설정 두 가지가 없으면 이 기능은 아무것도 정리하지 않고 로그만 남긴다**는 사실을 적는다.

`## 배포 영향`에 반드시 담을 것:

1. **버킷 수명주기 규칙 추가** — 대상: 태그 `orphan=true`, 동작: 객체 생성 30일 후 만료
2. **IAM 권한 추가** — 배포에 쓰는 자격증명에 `s3:PutObjectTagging`
3. 만료 기준은 **객체 생성 시각**이지 태깅 시각이 아니다. 오래전에 올린 사진을 지금 교체하면 태그가 붙는 즉시 만료 대상이 될 수 있다
4. 배포 후 프로필 사진을 한 번 바꿔보고 컨테이너 로그에 `이미지 orphan 태깅 실패`가 찍히지 않는지 확인해야 한다

`## 구현 노트 / 알려진 한계`에 담을 것:

- 확정되지 않은 업로드는 여전히 남는다(`pending/` prefix 도입은 별도 과제)
- 이미 쌓인 누적분은 그대로다. 앞으로 발생하는 것만 처리한다
- 코드는 삭제하지 않는다. 삭제는 수명주기 규칙이 한다 — 복구 창 30일을 남기기 위해서다

```bash
gh pr create --base develop --head feat/s3-orphan-cleanup --title "feat(s3): 참조가 끊긴 이미지에 orphan 태그 부착" --body-file <작성한 본문 파일>
```

- [ ] **Step 6: 머지는 사용자가 한다 — 체크포인트**

여기서 멈춘다. 머지 전에 사용자가 AWS 콘솔 작업(수명주기 규칙, IAM 권한)을 마쳐야 배포 후 확인이 의미를 갖는다.

머지 후 확인할 것:
- 프로필 사진을 바꾼 뒤 S3 콘솔에서 **옛 객체**에 `orphan=true` 태그가 붙었는지
- 컨테이너 로그(`docker compose logs --tail=100 app`)에 `이미지 orphan 태깅 실패`가 없는지
- 이미지가 붙은 메시지를 삭제한 뒤 그 객체에도 태그가 붙었는지
