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
        assertThat(ImageUploads.sanitizeFilename("../../etc/passwd")).isEqualTo("....etcpasswd");
        assertThat(ImageUploads.sanitizeFilename("my photo (1).png")).isEqualTo("myphoto1.png");
        assertThat(ImageUploads.sanitizeFilename("a\nb.png")).isEqualTo("ab.png");
    }

    @Test
    void 파일명이_비거나_전부_걸러지면_기본값을_쓴다() {
        assertThat(ImageUploads.sanitizeFilename(null)).isEqualTo("image");
        assertThat(ImageUploads.sanitizeFilename("///")).isEqualTo("image");
    }

    // 슬래시가 제거되므로 정규화된 이름은 키 접두사를 벗어날 수 없다. 남은 점은 무해하다.
    @Test
    void 정규화된_파일명에는_경로_구분자가_남지_않는다() {
        assertThat(ImageUploads.sanitizeFilename("../../etc/passwd")).doesNotContain("/");
        assertThat(ImageUploads.sanitizeFilename("a/b\\c.png")).doesNotContain("/", "\\");
    }

    @Test
    void 확장자_구분점을_지우지_않는다() {
        assertThat(ImageUploads.sanitizeFilename("photo..jpg")).isEqualTo("photo..jpg");
    }

    // 조립된 URL이 image_url·profile_image_url 컬럼(500자)을 넘으면 저장 시점에 500이 된다.
    @Test
    void 긴_파일명은_잘라낸다() {
        assertThat(ImageUploads.sanitizeFilename("a".repeat(400) + ".png")).hasSize(64);
    }

    @Test
    void 긴_파일명을_담은_키는_문법_검증에서_걸린다() {
        String uuid = "0e3d4f2a-1b6c-4d8e-9a0b-1c2d3e4f5a6b";
        assertThat(ImageUploads.KEY_SYNTAX.matcher("rooms/7/" + uuid + "_" + "a".repeat(64)).matches()).isTrue();
        assertThat(ImageUploads.KEY_SYNTAX.matcher("rooms/7/" + uuid + "_" + "a".repeat(65)).matches()).isFalse();
    }

    // 업로드가 만든 키는 언제나 쓰기 경계를 통과해야 한다.
    @Test
    void 잘라낸_파일명으로_조립한_키는_문법_검증을_통과한다() {
        String key = "rooms/7/0e3d4f2a-1b6c-4d8e-9a0b-1c2d3e4f5a6b_"
                + ImageUploads.sanitizeFilename("a".repeat(400) + ".png");
        assertThat(ImageUploads.KEY_SYNTAX.matcher(key).matches()).isTrue();
    }

    // 실제로 거대 이미지를 디코딩하면 테스트 JVM이 OOM 나므로, 한계값 자체는 숫자로만 검증한다.
    @Test
    void 천육백만_픽셀_이하는_통과한다() {
        ImageUploads.rejectIfTooLarge(4000, 4000); // 16,000,000
    }

    @Test
    void 천육백만_픽셀_초과는_거절한다() {
        assertThatThrownBy(() -> ImageUploads.rejectIfTooLarge(4001, 4000))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    void 일반적인_사진_크기는_통과한다() {
        ImageUploads.rejectIfTooLarge(4032, 3024); // 흔한 스마트폰 사진 해상도, 12.2MP
    }
}
