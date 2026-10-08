package com.example.springboot_realtimechat.domain.image.event;

import com.example.springboot_realtimechat.domain.image.service.OrphanImageTagger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupListener {

    private final OrphanImageTagger orphanImageTagger;

    // 커밋된 뒤에만 태깅한다. 트랜잭션 안에서 태깅하면 롤백되어도 태그가 S3에 남아,
    // 여전히 사용 중인 객체가 수명주기 규칙으로 만료된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onImageDereferenced(ImageDereferencedEvent event) {
        try {
            orphanImageTagger.tagIfUnreferenced(event.url());
        } catch (Exception e) {
            // 이 경로는 재시도 수단이 없다. 판단할 수 없을 때(질의 실패)도 태깅하지 않는 쪽이 안전하다.
            log.warn("이미지 정리 실패: url={}", event.url(), e);
        }
    }
}
