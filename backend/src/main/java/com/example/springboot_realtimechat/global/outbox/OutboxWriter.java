package com.example.springboot_realtimechat.global.outbox;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final EntityManager entityManager;

    /**
     * 이벤트를 outbox에 쓰고 곧바로 지운다. 행은 남지 않지만 binlog에는 INSERT가 남고 CDC가 그것을 발행한다.
     * 호출한 쪽의 트랜잭션에 참여해야 업무 변경과 이벤트가 함께 커밋되거나 함께 롤백된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event) {
        entityManager.persist(event);
        // 단계마다 SQL로 내보내 INSERT와 DELETE가 모두 실행되게 한다(쓰기 지연 중 상쇄에 기대지 않는다).
        entityManager.flush();
        entityManager.remove(event);
        entityManager.flush();
    }
}
