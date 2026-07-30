package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupListener {

    private final S3Service s3Service;

    // 커밋된 뒤에만 태깅한다. 트랜잭션 안에서 태깅하면 롤백되어도 태그가 S3에 남아,
    // 여전히 사용 중인 객체가 수명주기 규칙으로 만료된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onImageDereferenced(ImageDereferencedEvent event) {
        try {
            s3Service.tagAsOrphan(event.url());
        } catch (Exception e) {
            log.warn("이미지 orphan 태깅 실패: url={}", event.url(), e);
        }
    }
}
