package com.example.springboot_realtimechat.service;

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

    @Test
    void 프로필_업로드_키는_업로더_id를_담지_않는다() throws Exception {
        s3Service.upload(pngFile(), ImageUploads.Purpose.PROFILE, 42L);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(captor.getValue().key()).startsWith("profiles/");
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
}
