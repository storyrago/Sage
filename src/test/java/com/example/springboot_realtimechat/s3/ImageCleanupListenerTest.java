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
