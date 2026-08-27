package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.image.event.ImageCleanupListener;
import com.example.springboot_realtimechat.domain.image.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.domain.image.service.ImageReferences;
import com.example.springboot_realtimechat.domain.image.service.S3Service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 정리는 부가 작업이다. 태깅이 실패해도 이미 커밋된 본 작업에 영향을 주면 안 된다.
@SpringBootTest
class ImageCleanupListenerTest {

    @Autowired
    ImageCleanupListener listener;

    @MockitoBean
    S3Service s3Service;

    // 기본 stub은 false를 돌려주므로(미참조) 기존 태깅 경로 테스트에는 영향이 없다.
    @MockitoBean
    ImageReferences imageReferences;

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

    @Test
    void 참조_질의가_실패하면_태깅하지_않고_예외를_전파하지_않는다() {
        when(imageReferences.isReferenced(anyString())).thenThrow(new RuntimeException("DB 불통"));

        assertThatNoException()
                .isThrownBy(() -> listener.onImageDereferenced(new ImageDereferencedEvent(URL)));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }
}
