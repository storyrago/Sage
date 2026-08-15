# 방 이미지 접근 제어 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 방에 올린 이미지를 그 방의 참가자만 볼 수 있게 한다.

**Architecture:** S3 버킷 정책을 `profiles/*`만 공개로 좁히고, 채팅 이미지는 만료 1시간의 프리사인드 URL로만 읽게 한다. 인가는 새로 만들지 않고 메시지 경로에 이미 걸린 인가를 물려받는다 — 서명된 URL을 받았다는 것이 곧 그 방의 참가자라는 뜻이다. 공개로 남는 `profiles/`에 임의 파일이 올라가지 않도록 업로드 검증을 서버에 넣는다.

**Tech Stack:** Spring Boot 3 / AWS SDK for Java v2 (`software.amazon.awssdk:s3`, `S3Presigner` 포함) / React + TypeScript (Vite)

설계 문서: `docs/superpowers/specs/2026-08-15-room-image-access-design.md`

## Global Constraints

- 스키마 변경 금지. Flyway 마이그레이션을 추가하지 않는다.
- DB에 저장하는 값의 형태를 바꾸지 않는다. `messages.image_url`·`members.profile_image_url`은 계속 전체 URL을 담는다(설계 §4).
- 새 의존성을 추가하지 않는다. `S3Presigner`는 이미 있는 `software.amazon.awssdk:s3`에 포함돼 있다.
- 프로필 사진 관련 DTO 4개(`MemberResponse`·`PublicMemberResponse`·`ChatRoomMemberResponse`·`MessageResponse.profileImageUrl`)는 손대지 않는다.
- `profiles/` 키는 **서명하지 않는다.** 서명하면 응답마다 URL이 바뀌어 아바타 캐시가 무효화된다.
- 프리사인드 만료는 **1시간**.
- `POST /api/images`의 `purpose`는 **필수**다. 기본값을 두지 않는다.
- 백엔드 검증: `./gradlew test` / 프론트 검증: `cd frontend && npm test && npm run lint && npm run build`
- 커밋 메시지·주석에 배경 서사("누락됐다", "그래서 깨져 있었다")를 쓰지 않는다. 변경의 목적만 쓴다.
- 테스트에서 실제 AWS를 호출하지 않는다. `S3Client`·`S3Presigner`는 Mockito로 세운다.

## File Structure

| 파일 | 역할 | 변경 |
|---|---|---|
| `src/main/java/.../service/ImageUploads.java` | 업로드 파일 검증·파일명 정규화·키 조립 | 신규 |
| `src/main/java/.../service/S3Service.java` | 업로드(접두사 적용) + 프리사인드 URL 생성 | 수정 |
| `src/main/java/.../config/S3Config.java` | `S3Presigner` 빈 | 수정 |
| `src/main/java/.../controller/ImageController.java` | `purpose` 필수 파라미터 | 수정 |
| `src/main/java/.../global/exception/ErrorCode.java` | 업로드 거절 코드 | 수정 |
| `src/main/java/.../service/MessageResponseFactory.java` | `MessageResponse` 생성 + 이미지 서명 (유일한 생성 경로) | 신규 |
| `src/main/java/.../controller/MessageController.java` | 팩토리 사용(4곳) | 수정 |
| `src/main/java/.../controller/ChatMessageController.java` | 팩토리 사용(1곳) | 수정 |
| `src/test/java/.../service/ImageUploadsTest.java` | 검증·정규화 | 신규 |
| `src/test/java/.../service/S3ServiceTest.java` | 접두사·서명 분기 | 신규 |
| `frontend/src/lib/api.ts` | `uploadImage(token, file, purpose)` | 수정 |
| `frontend/src/components/ChatArea.tsx` | 업로드 purpose, 이미지 `onError` | 수정 |
| `frontend/src/lib/useProfilePhotoDraft.ts` | 업로드 purpose | 수정 |
| `frontend/src/App.tsx` | 만료 시 메시지 재조회 배선 | 수정 |
| `docs/ops/2026-08-15-image-access-cutover.md` | 배포 순서 런북(수동 작업) | 신규 |

---

### Task 1: 업로드 검증과 키 접두사

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/service/ImageUploads.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/S3Service.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ImageController.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java`
- Test: `src/test/java/com/example/springboot_realtimechat/service/ImageUploadsTest.java`

**Interfaces:**
- Consumes: 기존 `CustomException(ErrorCode)`, `S3Client`
- Produces:
  - `ImageUploads.Purpose` — `enum { PROFILE("profiles/"), CHAT("rooms/") }`, `String prefix()`
  - `ImageUploads.parsePurpose(String raw) : Purpose` — `profile`/`chat` 외에는 `CustomException(INVALID_IMAGE_PURPOSE)`
  - `ImageUploads.sanitizeFilename(String original) : String`
  - `ImageUploads.verifyImage(MultipartFile file) : String` — 검증된 content-type을 반환, 실패 시 `CustomException(INVALID_IMAGE)`
  - `S3Service.upload(MultipartFile file, ImageUploads.Purpose purpose) : String`

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`src/test/java/com/example/springboot_realtimechat/service/ImageUploadsTest.java` 신규 생성:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageUploadsTest {

    /** 실제로 디코딩되는 최소 PNG를 만든다. 바이트를 손으로 박으면 무엇을 검증하는지 흐려진다. */
    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void 용도는_profile과_chat만_받는다() {
        assertThat(ImageUploads.parsePurpose("profile")).isEqualTo(ImageUploads.Purpose.PROFILE);
        assertThat(ImageUploads.parsePurpose("chat")).isEqualTo(ImageUploads.Purpose.CHAT);
    }

    @Test
    void 용도가_없거나_모르는_값이면_거절한다() {
        assertThatThrownBy(() -> ImageUploads.parsePurpose(null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_PURPOSE);
        assertThatThrownBy(() -> ImageUploads.parsePurpose("avatar"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_PURPOSE);
    }

    @Test
    void 용도별_키_접두사() {
        assertThat(ImageUploads.Purpose.PROFILE.prefix()).isEqualTo("profiles/");
        assertThat(ImageUploads.Purpose.CHAT.prefix()).isEqualTo("rooms/");
    }

    @Test
    void 실제_이미지는_통과하고_검증된_타입을_돌려준다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", pngBytes());
        assertThat(ImageUploads.verifyImage(file)).isEqualTo("image/png");
    }

    @Test
    void 이미지가_아니면_거절한다() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "not an image".getBytes());
        assertThatThrownBy(() -> ImageUploads.verifyImage(file))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE);
    }

    // content-type은 클라이언트가 보내는 값이라 그것만 믿으면 임의 파일이 올라간다.
    @Test
    void content_type이_이미지라고_주장해도_내용이_아니면_거절한다() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.html", "image/png", "<html>hi</html>".getBytes());
        assertThatThrownBy(() -> ImageUploads.verifyImage(file))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE);
    }

    @Test
    void 허용하지_않는_content_type은_거절한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.bmp", "image/bmp", pngBytes());
        assertThatThrownBy(() -> ImageUploads.verifyImage(file))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE);
    }

    // 키가 그대로 URL 경로가 되므로 파일명이 키를 오염시키면 안 된다.
    @Test
    void 파일명에서_경로문자와_공백을_제거한다() {
        assertThat(ImageUploads.sanitizeFilename("../../etc/passwd")).isEqualTo("etcpasswd");
        assertThat(ImageUploads.sanitizeFilename("my photo (1).png")).isEqualTo("myphoto1.png");
        assertThat(ImageUploads.sanitizeFilename("a\nb.png")).isEqualTo("ab.png");
    }

    @Test
    void 파일명이_비거나_전부_걸러지면_기본값을_쓴다() {
        assertThat(ImageUploads.sanitizeFilename(null)).isEqualTo("image");
        assertThat(ImageUploads.sanitizeFilename("///")).isEqualTo("image");
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

```bash
./gradlew test --tests '*ImageUploadsTest*'
```

Expected: 컴파일 실패. `ImageUploads` 클래스와 `ErrorCode.INVALID_IMAGE`·`INVALID_IMAGE_PURPOSE`가 없다.

- [ ] **Step 3: 오류 코드를 추가한다**

`ErrorCode.java`의 마지막 상수 뒤에 다음 두 줄을 추가한다(기존 상수 사이에 끼워 넣지 말고 파일의 주석 구획 관례를 따라 `// Image` 구획을 새로 만든다):

```java
    // Image
    INVALID_IMAGE(400, "이미지 파일만 올릴 수 있어요."),
    INVALID_IMAGE_PURPOSE(400, "이미지 용도가 올바르지 않습니다."),
```

- [ ] **Step 4: `ImageUploads`를 구현한다**

`src/main/java/com/example/springboot_realtimechat/service/ImageUploads.java` 신규 생성:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** 업로드 파일 검증과 키 조립. 공개 접두사에 임의 파일이 올라가지 못하게 막는다. */
public final class ImageUploads {

    private ImageUploads() {}

    public enum Purpose {
        PROFILE("profiles/"),
        CHAT("rooms/");

        private final String prefix;

        Purpose(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /** 기본값을 두지 않는다. 용도를 잘못 지정한 업로드가 조용히 공개되면 안 된다. */
    public static Purpose parsePurpose(String raw) {
        if ("profile".equals(raw)) return Purpose.PROFILE;
        if ("chat".equals(raw)) return Purpose.CHAT;
        throw new CustomException(ErrorCode.INVALID_IMAGE_PURPOSE);
    }

    /**
     * 실제로 디코딩되는 이미지인지 확인하고, 검증된 content-type을 돌려준다.
     * content-type은 클라이언트가 보내는 값이라 그것만 믿으면 임의 파일이 올라간다.
     */
    public static String verifyImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_IMAGE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new CustomException(ErrorCode.INVALID_IMAGE);
        }
        try (InputStream in = file.getInputStream()) {
            BufferedImage decoded = ImageIO.read(in);
            if (decoded == null) {
                throw new CustomException(ErrorCode.INVALID_IMAGE);
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_IMAGE);
        }
        return contentType;
    }

    /** 키가 그대로 URL 경로가 되므로 영숫자·점·하이픈만 남긴다. */
    public static String sanitizeFilename(String original) {
        if (original == null) return "image";
        String cleaned = original.replaceAll("[^A-Za-z0-9.-]", "");
        return cleaned.isBlank() ? "image" : cleaned;
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

```bash
./gradlew test --tests '*ImageUploadsTest*'
```

Expected: 9개 테스트 전부 PASS.

- [ ] **Step 6: `S3Service.upload`가 접두사와 검증을 쓰게 한다**

`S3Service.java`의 `upload` 메서드를 다음으로 바꾼다:

```java
    public String upload(MultipartFile file, ImageUploads.Purpose purpose) {
        String contentType = ImageUploads.verifyImage(file);
        String key = purpose.prefix() + UUID.randomUUID() + "_" + ImageUploads.sanitizeFilename(file.getOriginalFilename());

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)   // 클라이언트가 보낸 값이 아니라 검증된 값을 쓴다
                .build();

        try {
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException("S3 업로드 실패", e);
        }

        return publicUrlPrefix() + key;
    }
```

- [ ] **Step 7: 컨트롤러가 `purpose`를 필수로 받게 한다**

`ImageController.java`의 `upload` 메서드를 다음으로 바꾼다:

```java
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", required = false) String purpose) {
        String url = s3Service.upload(file, ImageUploads.parsePurpose(purpose));
        return ResponseEntity.ok(Map.of("url", url));
    }
```

`required = false`로 받아 `parsePurpose`가 판정하게 한다. `required = true`로 두면 누락 시 스프링이 먼저 500/400을 내어 `INVALID_IMAGE_PURPOSE` 코드가 나가지 않는다.

`import com.example.springboot_realtimechat.service.ImageUploads;`를 추가한다.

- [ ] **Step 8: 백엔드 전체 테스트를 돌린다**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. 실패하면 `s3Service.upload(` 호출 지점이 더 있는지 확인한다:

```bash
grep -rn "s3Service.upload\|\.upload(" src/main src/test | grep -i image
```

- [ ] **Step 9: 커밋한다**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/ImageUploads.java \
        src/main/java/com/example/springboot_realtimechat/service/S3Service.java \
        src/main/java/com/example/springboot_realtimechat/controller/ImageController.java \
        src/main/java/com/example/springboot_realtimechat/global/exception/ErrorCode.java \
        src/test/java/com/example/springboot_realtimechat/service/ImageUploadsTest.java
git commit -m "feat(image): 업로드를 검증하고 용도별 키 접두사로 저장한다"
```

---

### Task 2: 프리사인드 URL 생성

**Files:**
- Modify: `src/main/java/com/example/springboot_realtimechat/config/S3Config.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/service/S3Service.java`
- Test: `src/test/java/com/example/springboot_realtimechat/service/S3ServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `ImageUploads.Purpose`
- Produces: `S3Service.presignedGetUrl(String storedUrl, Duration ttl) : String`

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`src/test/java/com/example/springboot_realtimechat/service/S3ServiceTest.java` 신규 생성:

```java
package com.example.springboot_realtimechat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "ap-northeast-2";
    private static final String PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    private S3Presigner presigner;
    private S3Service s3Service;

    @BeforeEach
    void setUp() throws Exception {
        presigner = mock(S3Presigner.class);
        s3Service = new S3Service(mock(S3Client.class), presigner);
        ReflectionTestUtils.setField(s3Service, "bucket", BUCKET);
        ReflectionTestUtils.setField(s3Service, "region", REGION);

        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(new URL("https://signed.example.com/x?sig=1"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);
    }

    @Test
    void 채팅_이미지는_서명한다() {
        String result = s3Service.presignedGetUrl(PREFIX + "rooms/abc_a.png", Duration.ofHours(1));

        assertThat(result).isEqualTo("https://signed.example.com/x?sig=1");
        verify(presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    // 접두사 도입 이전에 올라간 채팅 이미지는 버킷 루트에 평면으로 있다.
    @Test
    void 접두사가_없는_기존_키도_서명한다() {
        String result = s3Service.presignedGetUrl(PREFIX + "abc_a.png", Duration.ofHours(1));

        assertThat(result).isEqualTo("https://signed.example.com/x?sig=1");
        verify(presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    // 서명하면 응답마다 URL이 바뀌어 아바타 캐시가 무효화된다.
    @Test
    void 프로필_사진은_서명하지_않는다() {
        String url = PREFIX + "profiles/abc_a.png";

        assertThat(s3Service.presignedGetUrl(url, Duration.ofHours(1))).isEqualTo(url);
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void 우리_버킷이_아닌_URL은_그대로_돌려준다() {
        String url = "https://example.com/somewhere/a.png";

        assertThat(s3Service.presignedGetUrl(url, Duration.ofHours(1))).isEqualTo(url);
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void null과_빈_문자열은_그대로_돌려준다() {
        assertThat(s3Service.presignedGetUrl(null, Duration.ofHours(1))).isNull();
        assertThat(s3Service.presignedGetUrl("", Duration.ofHours(1))).isEmpty();
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

```bash
./gradlew test --tests '*S3ServiceTest*'
```

Expected: 컴파일 실패. `S3Service`에 2-인자 생성자와 `presignedGetUrl`이 없다.

- [ ] **Step 3: `S3Presigner` 빈을 추가한다**

`S3Config.java`에 다음 빈을 추가한다(`import software.amazon.awssdk.services.s3.presigner.S3Presigner;` 포함):

```java
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }
```

- [ ] **Step 4: `presignedGetUrl`을 구현한다**

`S3Service.java`에 `S3Presigner` 필드를 **`s3Client` 다음에** 추가하고(`@RequiredArgsConstructor`가 필드 선언 순서로 생성자를 만들므로 순서가 테스트와 일치해야 한다), 다음 메서드를 추가한다:

```java
    private final S3Presigner s3Presigner;

    /**
     * 채팅 이미지는 서명된 URL로만 읽는다.
     * profiles/는 공개이므로 서명하지 않는다 — 서명하면 응답마다 URL이 바뀌어 아바타 캐시가 무효화된다.
     * 우리 버킷이 아닌 값은 그대로 돌려준다(본문에 박힌 외부 URL 하위호환).
     */
    public String presignedGetUrl(String storedUrl, Duration ttl) {
        String key = extractKey(storedUrl);
        if (key == null) return storedUrl;
        if (key.startsWith(ImageUploads.Purpose.PROFILE.prefix())) return storedUrl;

        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }
```

필요한 import:

```java
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.time.Duration;
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

```bash
./gradlew test --tests '*S3ServiceTest*'
```

Expected: 5개 테스트 전부 PASS.

- [ ] **Step 6: 애플리케이션 컨텍스트가 뜨는지 확인한다**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. `S3Presigner` 빈 생성이 실패하면 컨텍스트 로드 테스트가 먼저 깨진다. 테스트 환경에 AWS 자격증명이 없어도 `S3Presigner.builder().region(...).build()`는 자격증명을 즉시 요구하지 않는다.

- [ ] **Step 7: 커밋한다**

```bash
git add src/main/java/com/example/springboot_realtimechat/config/S3Config.java \
        src/main/java/com/example/springboot_realtimechat/service/S3Service.java \
        src/test/java/com/example/springboot_realtimechat/service/S3ServiceTest.java
git commit -m "feat(image): 비공개 객체를 읽을 수 있는 서명 URL 생성을 추가한다"
```

---

### Task 3: 메시지 응답에 서명 배선

**Files:**
- Create: `src/main/java/com/example/springboot_realtimechat/service/MessageResponseFactory.java`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/MessageController.java:36,50,63,74`
- Modify: `src/main/java/com/example/springboot_realtimechat/controller/ChatMessageController.java:42`
- Test: `src/test/java/com/example/springboot_realtimechat/service/MessageResponseFactoryTest.java`

**Interfaces:**
- Consumes: Task 2의 `S3Service.presignedGetUrl(String, Duration)`
- Produces: `MessageResponseFactory.of(Message) : MessageResponse`

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`src/test/java/com/example/springboot_realtimechat/service/MessageResponseFactoryTest.java` 신규 생성:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageResponseFactoryTest {

    private Message messageWithImage(String imageUrl) {
        Member author = mock(Member.class);
        when(author.getId()).thenReturn(1L);
        when(author.getNickname()).thenReturn("작성자");
        when(author.getProfileImageUrl()).thenReturn("https://bucket/profiles/me.png");

        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(7L);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(11L);
        when(message.getContent()).thenReturn("본문");
        when(message.getImageUrl()).thenReturn(imageUrl);
        when(message.getMember()).thenReturn(author);
        when(message.getChatRoom()).thenReturn(room);
        return message;
    }

    @Test
    void 이미지_URL을_서명된_URL로_바꾼다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(eq("https://bucket/rooms/a.png"), any(Duration.class)))
                .thenReturn("https://signed/a.png?sig=1");

        MessageResponse response = new MessageResponseFactory(s3Service)
                .of(messageWithImage("https://bucket/rooms/a.png"));

        assertThat(response.getImageUrl()).isEqualTo("https://signed/a.png?sig=1");
    }

    @Test
    void 서명_만료는_1시간이다() {
        S3Service s3Service = mock(S3Service.class);
        new MessageResponseFactory(s3Service).of(messageWithImage("https://bucket/rooms/a.png"));

        verify(s3Service).presignedGetUrl(eq("https://bucket/rooms/a.png"), eq(Duration.ofHours(1)));
    }

    // 프로필 사진은 서명 대상이 아니다. 서명 여부 판정은 S3Service가 하므로 팩토리는 건드리지 않는다.
    @Test
    void 작성자_프로필_사진은_그대로_둔다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(any(), any(Duration.class))).thenReturn("서명됨");

        MessageResponse response = new MessageResponseFactory(s3Service)
                .of(messageWithImage("https://bucket/rooms/a.png"));

        assertThat(response.getProfileImageUrl()).isEqualTo("https://bucket/profiles/me.png");
    }

    @Test
    void 이미지가_없는_메시지도_처리한다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(isNull(), any(Duration.class))).thenReturn(null);

        MessageResponse response = new MessageResponseFactory(s3Service).of(messageWithImage(null));

        assertThat(response.getImageUrl()).isNull();
        assertThat(response.getMessageId()).isEqualTo(11L);
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

```bash
./gradlew test --tests '*MessageResponseFactoryTest*'
```

Expected: 컴파일 실패. `MessageResponseFactory`가 없다.

- [ ] **Step 3: 팩토리를 구현한다**

`src/main/java/com/example/springboot_realtimechat/service/MessageResponseFactory.java` 신규 생성:

```java
package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 응답으로 나가는 메시지의 이미지 URL을 서명한다.
 * 만료는 액세스 토큰 수명과 같은 1시간 — 세션이 살아있는 동안 유효하다는 규칙 하나로 맞춘다.
 *
 * MessageResponse를 만드는 유일한 경로다. 서명하지 않고 응답을 만들면 그 경로의 이미지는 보이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MessageResponseFactory {

    public static final Duration IMAGE_URL_TTL = Duration.ofHours(1);

    private final S3Service s3Service;

    public MessageResponse of(Message message) {
        MessageResponse response = MessageResponse.from(message);
        response.setImageUrl(s3Service.presignedGetUrl(response.getImageUrl(), IMAGE_URL_TTL));
        return response;
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

```bash
./gradlew test --tests '*MessageResponseFactoryTest*'
```

Expected: 4개 테스트 전부 PASS.

- [ ] **Step 5: 호출 지점 5곳을 팩토리로 바꾼다**

먼저 남아 있는 지점을 전부 찾는다:

```bash
grep -rn "MessageResponse.from" src/main
```

`MessageController.java`에 `private final MessageResponseFactory messageResponseFactory;` 필드를 추가하고, 4곳을 바꾼다:

- `:36` — `MessageResponse response = messageResponseFactory.of(message);`
- `:50` 부근의 스트림 — `.map(messageResponseFactory::of)`
- `:63` — `MessageResponse response = messageResponseFactory.of(message);`
- `:74` — `MessageResponse response = messageResponseFactory.of(message);`

`ChatMessageController.java`에도 같은 필드를 추가하고 `:42`를 바꾼다:

```java
        MessageResponse messageResponse = messageResponseFactory.of(message);
```

두 파일에 `import com.example.springboot_realtimechat.service.MessageResponseFactory;`를 추가한다.

- [ ] **Step 6: 프로덕션 코드에 직접 호출이 남지 않았는지 확인한다**

```bash
grep -rn "MessageResponse.from" src/main
```

Expected: `MessageResponseFactory.java` 한 줄만 나온다. 다른 줄이 남아 있으면 그 경로의 이미지는 서명되지 않아 보이지 않는다.

- [ ] **Step 7: 백엔드 전체 테스트를 돌린다**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: 커밋한다**

```bash
git add src/main/java/com/example/springboot_realtimechat/service/MessageResponseFactory.java \
        src/main/java/com/example/springboot_realtimechat/controller/MessageController.java \
        src/main/java/com/example/springboot_realtimechat/controller/ChatMessageController.java \
        src/test/java/com/example/springboot_realtimechat/service/MessageResponseFactoryTest.java
git commit -m "feat(image): 메시지 응답의 이미지 URL을 서명해서 내보낸다"
```

---

### Task 4: 프론트 — 업로드 용도와 만료 대응

**Files:**
- Modify: `frontend/src/lib/api.ts:262-275`
- Modify: `frontend/src/lib/useProfilePhotoDraft.ts:51`
- Modify: `frontend/src/components/ChatArea.tsx:270`, `:588-614`
- Modify: `frontend/src/App.tsx` (ChatArea props)

**Interfaces:**
- Consumes: Task 1의 `purpose` 파라미터(`profile` | `chat`)
- Produces: `uploadImage(token, file, purpose: 'profile' | 'chat')`, `ChatArea` prop `onImageExpired?: () => void`

- [ ] **Step 1: `uploadImage`가 용도를 받게 한다**

`frontend/src/lib/api.ts:262`의 시그니처와 URL을 바꾼다:

```ts
export async function uploadImage(token: string, file: File, purpose: 'profile' | 'chat'): Promise<string> {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch(`${API_BASE_URL}/api/images?purpose=${purpose}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` }, // Content-Type은 브라우저가 boundary 포함해 자동 설정
    body: form,
  });
  if (!res.ok) {
    await throwApiError(res);
  }
  const data = (await res.json()) as { url: string };
  return data.url;
```

- [ ] **Step 2: 호출 지점 2곳에 용도를 넣는다**

`frontend/src/lib/useProfilePhotoDraft.ts:51`:

```ts
          uploadedUrlRef.current = await uploadImage(token, file, 'profile');
```

`frontend/src/components/ChatArea.tsx:270`:

```ts
      const url = await uploadImage(token, file, 'chat');
```

- [ ] **Step 3: 타입 검사로 빠진 호출이 없는지 확인한다**

```bash
cd frontend && npm run lint
```

Expected: exit 0. 인자를 빠뜨린 호출이 있으면 여기서 잡힌다(이 레포에서 `tsc`가 실제로 잡아주는 몇 안 되는 경우다 — 인자 개수는 `@types/react` 없이도 검사된다).

- [ ] **Step 4: 만료된 이미지를 재조회하는 prop을 `ChatArea`에 추가한다**

`ChatArea.tsx`의 Props 인터페이스에 추가한다:

```ts
  onImageExpired?: () => void;
```

구조분해에도 추가한다(`onLoadOlder,` 옆):

```ts
  onImageExpired,
```

컴포넌트 안에 재시도 가드를 둔다(`handleImageLoad` 선언 근처):

```ts
  // 서명 URL이 만료되면 이미지가 깨진다. 메시지를 다시 불러오면 새 서명이 온다.
  // 재시도는 한 번만 한다 — 진짜로 깨진 이미지에서 무한 재조회가 돌면 안 된다.
  const imageRetriedRef = useRef(false);
  const handleImageError = () => {
    if (imageRetriedRef.current) return;
    imageRetriedRef.current = true;
    onImageExpired?.();
  };
```

`useRef`가 이미 import돼 있는지 확인하고 없으면 추가한다.

- [ ] **Step 5: 두 이미지 렌더 지점에 `onError`를 단다**

`ChatArea.tsx:588` 블록의 `<img>`에 `onLoad={handleImageLoad}` 옆으로 추가한다:

```tsx
                          onError={handleImageError}
```

`:602` 블록(본문에 박힌 URL)의 `<img>`에도 같은 줄을 추가한다.

- [ ] **Step 6: `App.tsx`에서 재조회를 연결한다**

`App.tsx`의 `<ChatArea ... />`에 prop을 추가한다(`onLoadOlder` 근처):

```tsx
              onImageExpired={() => {
                if (!token || !selectedChannelId) return;
                getMessages(token, selectedChannelId)
                  .then((page) => setMessages(page.messages.map(toMessage)))
                  .catch((e) => console.error('[Image] 만료된 이미지 갱신 실패:', e));
              }}
```

`getMessages`와 `toMessage`는 이미 import돼 있다. 반환 형태가 `:309` 부근의 기존 사용과 같은지 그 코드를 읽고 맞춘다 — 필드 이름이 다르면 그 코드를 따른다.

- [ ] **Step 7: 프론트 검증**

```bash
cd frontend && npm test && npm run lint && npm run build
```

Expected: 셋 다 통과.

⚠️ 이 레포는 `@types/react`가 없고 tsconfig에 `strict`도 없어 `tsc`가 잡는 게 거의 없다. 통과했다고 배선이 맞다고 말하지 말고, 디프를 직접 읽어 확인한다.

- [ ] **Step 8: 커밋한다**

```bash
git add frontend/src/lib/api.ts frontend/src/lib/useProfilePhotoDraft.ts \
        frontend/src/components/ChatArea.tsx frontend/src/App.tsx
git commit -m "feat(frontend): 이미지 업로드에 용도를 실어 보내고 만료된 이미지를 다시 불러온다"
```

---

### Task 5: 전환 런북과 최종 검증

**Files:**
- Create: `docs/ops/2026-08-15-image-access-cutover.md`

**Interfaces:** 없음(문서와 검증)

- [ ] **Step 1: 런북을 작성한다**

`docs/ops/2026-08-15-image-access-cutover.md` 신규 생성. 아래 내용을 그대로 넣는다:

````markdown
# 이미지 접근 제어 전환 런북

설계: `docs/superpowers/specs/2026-08-15-room-image-access-design.md`

수동 작업이 둘 있고 **순서를 지켜야 한다.** 3을 먼저 하면 이관 전 프로필 사진이 전부 깨진다.

## 1. 코드 배포

develop 머지 → CD. 이 시점에는 버킷 정책이 아직 전체 공개라 아무것도 깨지지 않는다.
새 업로드부터 `profiles/`·`rooms/` 접두사가 붙는다.

## 2. 기존 프로필 사진 이관

기존 객체는 버킷 루트에 평면으로 있다. 프로필 사진만 `profiles/`로 옮긴다.
채팅 이미지는 옮기지 않는다 — 루트에 두면 새 정책에서 자동으로 비공개가 되고, 서명은 키만 알면 된다.

옮길 대상 확인(RDS):

```sql
SELECT id, profile_image_url FROM members WHERE profile_image_url IS NOT NULL;
```

각 URL의 마지막 `/` 뒤가 키다. 키마다 복사한다:

```bash
aws s3 cp "s3://realtimechat-images-storyrago/<KEY>" "s3://realtimechat-images-storyrago/profiles/<KEY>"
```

DB를 갱신한다(복사가 전부 끝난 뒤에 한 번에):

```sql
UPDATE members
SET profile_image_url = REPLACE(
      profile_image_url,
      'amazonaws.com/',
      'amazonaws.com/profiles/')
WHERE profile_image_url LIKE '%amazonaws.com/%'
  AND profile_image_url NOT LIKE '%amazonaws.com/profiles/%';
```

`NOT LIKE`가 있어야 두 번 실행해도 `profiles/profiles/`가 되지 않는다.

갱신 결과가 실제 객체와 맞는지 확인한 뒤 원본을 지운다:

```bash
aws s3 rm "s3://realtimechat-images-storyrago/<KEY>"
```

## 3. 버킷 정책 교체

AWS 콘솔 → S3 → `realtimechat-images-storyrago` → 권한 → 버킷 정책.
기존 "버킷 전체 `s3:GetObject` 공개"를 다음으로 바꾼다:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadProfiles",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::realtimechat-images-storyrago/profiles/*"
    }
  ]
}
```

## 4. 확인

```bash
# 프로필 사진: 서명 없이 200
curl -s -o /dev/null -w '%{http_code}\n' "https://realtimechat-images-storyrago.s3.ap-northeast-2.amazonaws.com/profiles/<KEY>"

# 채팅 이미지: 서명 없이 403
curl -s -o /dev/null -w '%{http_code}\n' "https://realtimechat-images-storyrago.s3.ap-northeast-2.amazonaws.com/<CHAT_KEY>"
```

앱에서: 아바타가 보이고, 채팅 이미지도 보이고, 채팅 이미지 URL을 로그아웃한 브라우저에 붙여넣으면 403.

## 롤백

버킷 정책을 원래대로(버킷 전체 공개) 되돌리면 즉시 원복된다. 코드는 그대로 두어도 무해하다.
````

- [ ] **Step 2: 백엔드 전체 테스트**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. 통과한 테스트 수를 기록한다(PR 본문의 `## 검증`에 쓴다).

- [ ] **Step 3: 프론트 전체 검증**

```bash
cd frontend && npm test && npm run lint && npm run build
```

Expected: 셋 다 통과. 테스트 수를 기록한다.

- [ ] **Step 4: 뮤테이션으로 테스트가 실제로 잠그는지 확인한다**

각각 되돌려 보고 **테스트가 빨간지** 확인한 뒤 원복한다.

| 되돌릴 것 | 빨개져야 할 테스트 |
|---|---|
| `verifyImage`의 `ImageIO.read` 검사 제거 | `content_type이_이미지라고_주장해도_내용이_아니면_거절한다` |
| `presignedGetUrl`의 `profiles/` 분기 제거 | `프로필_사진은_서명하지_않는다` |
| `MessageResponseFactory.of`가 서명 없이 `MessageResponse.from`만 반환 | `이미지_URL을_서명된_URL로_바꾼다` |
| `parsePurpose`가 null을 `CHAT`으로 기본 처리 | `용도가_없거나_모르는_값이면_거절한다` |

- [ ] **Step 5: 커밋하고 PR을 만든다**

```bash
git add docs/ops/2026-08-15-image-access-cutover.md
git commit -m "docs: 이미지 접근 제어 전환 런북을 추가한다"
git push -u origin feat/image-access-control
```

`.github/pull_request_template.md`의 5개 섹션을 같은 순서·같은 제목으로 채운다.
`## 검증`에는 **실제로 실행한 것만** 쓴다. 실제 S3 왕복을 확인하지 않았으면 안 했다고 적는다.
`## 배포 영향`에 **수동 작업 2건(프로필 이관, 버킷 정책 교체)과 순서**를 명시하고 런북을 링크한다.

리뷰어가 놓치면 위험한 변경이므로 `## 검증` 바로 앞에 `## 리뷰어가 꼭 봐야 할 변경`을 넣고
버킷 정책 교체 순서를 적는다.

base는 `develop`이다.

---

## Self-Review

**스펙 커버리지**

| 스펙 항목 | 담당 태스크 |
|---|---|
| §1 프록시를 쓰지 않는 이유 | 설계 근거 — 구현 없음 |
| §2 기존 인가를 물려받음 | 새 인가 코드를 만들지 않는 것으로 충족(Task 3) |
| §3 키 접두사·`purpose` 필수 | Task 1 |
| §3 버킷 정책 교체 | Task 5 런북 |
| §4 DB 저장 형태 유지 | Global Constraints(마이그레이션 금지) |
| §5 서명 3분기 | Task 2 |
| §5 서명 지점 5곳 통합 | Task 3 (Step 6에서 잔존 호출 확인) |
| §5 만료 1시간 | Task 3 `IMAGE_URL_TTL` + 테스트 |
| §6 `onError` 1회 재시도 | Task 4 |
| §7 업로드 검증 4종 | Task 1 |
| §8 검증 | Task 5 |
| §9 배포 순서 | Task 5 런북 |
| §10 알려진 한계 | PR 본문 |

**타입 일관성**

- `ImageUploads.Purpose` — Task 1 정의, Task 2의 `presignedGetUrl`이 `PROFILE.prefix()` 사용
- `S3Service.presignedGetUrl(String, Duration)` — Task 2 정의, Task 3에서 호출
- `MessageResponseFactory.of(Message)` — Task 3 정의, 컨트롤러 5곳에서 사용
- `purpose: 'profile' | 'chat'` — Task 1의 서버 값과 Task 4의 프론트 리터럴이 일치

**남은 위험**

- 실제 S3 왕복(서명 URL 200 / 무서명 403 / 만료 후 403)은 자격증명이 필요해 자동 테스트로 덮지 못한다.
  Task 5 런북의 §4가 그 자리를 대신하고, 사람이 실행한다.
