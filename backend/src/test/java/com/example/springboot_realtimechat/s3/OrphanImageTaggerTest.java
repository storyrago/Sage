package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.image.service.ImageReferences;
import com.example.springboot_realtimechat.domain.image.service.OrphanImageTagger;
import com.example.springboot_realtimechat.domain.image.service.S3Service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 판단만 한다. 실패를 삼킬지 다시 시도할지는 호출자(커밋 후 리스너, Kafka 소비자)가 정한다.
class OrphanImageTaggerTest {

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/rooms/1/00000000-0000-0000-0000-0000000000f1_a.png";

    private final ImageReferences imageReferences = mock(ImageReferences.class);
    private final S3Service s3Service = mock(S3Service.class);
    private final OrphanImageTagger tagger = new OrphanImageTagger(imageReferences, s3Service);

    @Test
    void 아무도_참조하지_않으면_태깅한다() {
        when(imageReferences.isReferenced(URL)).thenReturn(false);

        tagger.tagIfUnreferenced(URL);

        verify(s3Service).tagAsOrphan(URL);
    }

    @Test
    void 아직_참조가_남아_있으면_태깅하지_않는다() {
        when(imageReferences.isReferenced(URL)).thenReturn(true);

        tagger.tagIfUnreferenced(URL);

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 참조_질의가_실패하면_태깅하지_않고_예외를_올린다() {
        when(imageReferences.isReferenced(URL)).thenThrow(new RuntimeException("DB 불통"));

        assertThatThrownBy(() -> tagger.tagIfUnreferenced(URL)).hasMessage("DB 불통");
        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 태깅이_실패하면_예외를_올린다() {
        doThrow(new RuntimeException("S3 불통")).when(s3Service).tagAsOrphan(URL);

        assertThatThrownBy(() -> tagger.tagIfUnreferenced(URL)).hasMessage("S3 불통");
    }
}
