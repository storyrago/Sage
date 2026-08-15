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
