package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionRevocationListener {

    private final RoomSubscriptionRevoker revoker;

    // 커밋된 뒤에만 회수한다. 트랜잭션 안에서 회수하면 롤백되어도 구독은 이미 지워진다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomLeft(RoomLeftEvent event) {
        try {
            revoker.revokeRoom(event.memberId(), event.roomId(), event.reason());
        } catch (Exception e) {
            log.warn("방 구독 회수 실패: memberId={}, roomId={}", event.memberId(), event.roomId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberDeleted(MemberDeletedEvent event) {
        try {
            revoker.revokeAll(event.memberId(), ErrorCode.ROOM_MEMBERSHIP_REVOKED);
        } catch (Exception e) {
            log.warn("회원 구독 회수 실패: memberId={}", event.memberId(), e);
        }
    }
}
