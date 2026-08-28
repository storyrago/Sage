package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.image.service.ImageUploads;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "ap-northeast-2";
    private static final String PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    private S3Client s3Client;
    private S3Presigner presigner;
    private S3Service s3Service;

    @BeforeEach
    void setUp() throws Exception {
        s3Client = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        s3Service = new S3Service(s3Client, presigner);
        ReflectionTestUtils.setField(s3Service, "bucket", BUCKET);
        ReflectionTestUtils.setField(s3Service, "region", REGION);

        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(new URL("https://signed.example.com/x?sig=1"));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);
    }

    private MockMultipartFile pngFile() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "a.png", "image/png", out.toByteArray());
    }

    @Test
    void 채팅_업로드_키는_업로더_id를_담는다() throws Exception {
        s3Service.upload(pngFile(), ImageUploads.Purpose.CHAT, 42L);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(captor.getValue().key()).startsWith("rooms/42/");
    }

    // 공개 버킷 정책(profiles/*)과 무서명 판정은 접두사만 보므로 업로더를 넣어도 그대로 매칭된다.
    @Test
    void 프로필_업로드_키도_업로더_id를_담는다() throws Exception {
        s3Service.upload(pngFile(), ImageUploads.Purpose.PROFILE, 42L);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(captor.getValue().key()).startsWith("profiles/42/");
        assertThat(ImageUploads.KEY_SYNTAX.matcher(captor.getValue().key()).matches()).isTrue();
    }

    private static final String OWN_KEY = "rooms/7/0e3d4f2a-1b6c-4d8e-9a0b-1c2d3e4f5a6b_a.png";

    @Test
    void 자신의_접두사로_시작하는_업로드_키는_통과한다() {
        assertThatCode(() -> s3Service.requireOwnKey(PREFIX + OWN_KEY, "rooms/7/"))
                .doesNotThrowAnyException();
    }

    @Test
    void 기대_접두사가_다르면_거부한다() {
        assertRejected(PREFIX + OWN_KEY, "profiles/7/");
    }

    @Test
    void 우리_버킷이_아닌_URL은_거부한다() {
        assertRejected("https://example.com/somewhere/a.png", "rooms/7/");
    }

    @Test
    void 경로형_URL은_거부한다() {
        assertRejected("https://s3.ap-northeast-2.amazonaws.com/test-bucket/" + OWN_KEY, "rooms/7/");
    }

    @Test
    void 접두사_안의_dot_dot_경로는_거부한다() {
        assertRejected(PREFIX + "rooms/7/../6/secret.png", "rooms/7/");
    }

    @Test
    void 소유자가_없는_평면_키는_거부한다() {
        assertRejected(PREFIX + "abc_photo.png", "rooms/7/");
    }

    @Test
    void null과_빈_문자열은_거부한다() {
        assertRejected(null, "rooms/7/");
        assertRejected("", "rooms/7/");
    }

    private void assertRejected(String url, String expectedPrefix) {
        assertThatThrownBy(() -> s3Service.requireOwnKey(url, expectedPrefix))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    @Test
    void 서명_요청은_전달받은_ttl을_그대로_싣는다() {
        Duration ttl = Duration.ofMinutes(17);
        s3Service.presignedGetUrl(PREFIX + "rooms/1/a.png", ttl);

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().signatureDuration()).isEqualTo(ttl);
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

    // extractKey가 인식하지 못하는 URL 형태는 서명 대상에서도 빠진다.
    // MessageService.create의 인증 검사도 같은 extractKey를 쓰므로, 인증을 통과한 값은 여기서도 그대로 통과할 뿐 서명되지 않는다.
    @Test
    void 경로형_URL은_서명하지_않고_그대로_돌려준다() {
        String pathStyleUrl = "https://s3.ap-northeast-2.amazonaws.com/test-bucket/rooms/999/x.png";

        assertThat(s3Service.presignedGetUrl(pathStyleUrl, Duration.ofHours(1))).isEqualTo(pathStyleUrl);
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
    }
}
