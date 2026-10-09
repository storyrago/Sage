package com.example.springboot_realtimechat.domain.image.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 참조가 끊긴 이미지를 정리 대상(orphan 태그)으로 표시한다. 다른 행이 아직 참조하면 살아 있는 객체라 건드리지 않는다.
 * 판단은 호출 시점의 DB 상태로 하므로 같은 URL로 여러 번 불러도 결과가 같다.
 * 실패(참조 질의·태깅)는 그대로 올린다 — 삼킬지 다시 시도할지는 호출자가 정한다.
 */
@Component
@RequiredArgsConstructor
public class OrphanImageTagger {

    private final ImageReferences imageReferences;
    private final S3Service s3Service;

    public void tagIfUnreferenced(String url) {
        if (imageReferences.isReferenced(url)) {
            return;
        }
        s3Service.tagAsOrphan(url);
    }
}
