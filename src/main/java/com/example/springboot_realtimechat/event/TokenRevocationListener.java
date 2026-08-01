package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.security.TokenDenylist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationListener {

    private final TokenDenylist tokenDenylist;

    // 커밋된 뒤에만 무효화한다. 롤백된 탈퇴로 토큰을 죽이면 멀쩡한 사용자가 로그아웃된다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberDeleted(MemberDeletedEvent event) {
        try {
            tokenDenylist.revokeMember(event.memberId());
        } catch (Exception e) {
            // 삭제는 이미 커밋됐고 되돌릴 수 없다. 무효화 실패를 던져도 되살릴 것이 없다.
            log.warn("탈퇴 회원 토큰 무효화 실패: memberId={}", event.memberId(), e);
        }
    }
}
