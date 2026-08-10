package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.event.MemberDeletedEvent;
import com.example.springboot_realtimechat.event.RoomLeftEvent;
import com.example.springboot_realtimechat.event.SubscriptionRevocationListener;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SubscriptionRevocationListenerTest {

    private RoomSubscriptionRevoker revoker;
    private SubscriptionRevocationListener listener;

    @BeforeEach
    void setUp() {
        revoker = mock(RoomSubscriptionRevoker.class);
        listener = new SubscriptionRevocationListener(revoker);
    }

    @Test
    void 방을_나가면_그_방_구독을_회수한다() {
        listener.onRoomLeft(new RoomLeftEvent(7L, 3L, ErrorCode.ROOM_MEMBERSHIP_REVOKED));

        verify(revoker).revokeRoom(7L, 3L, ErrorCode.ROOM_MEMBERSHIP_REVOKED);
    }

    @Test
    void 회원이_탈퇴하면_모든_방_구독을_회수한다() {
        listener.onMemberDeleted(new MemberDeletedEvent(7L));

        verify(revoker).revokeAll(7L, ErrorCode.ROOM_MEMBERSHIP_REVOKED);
    }

    @Test
    void 회수가_실패해도_예외를_밖으로_내보내지_않는다() {
        doThrow(new IllegalStateException("boom"))
                .when(revoker).revokeRoom(7L, 3L, ErrorCode.ROOM_MEMBERSHIP_REVOKED);

        assertThatCode(() -> listener.onRoomLeft(new RoomLeftEvent(7L, 3L, ErrorCode.ROOM_MEMBERSHIP_REVOKED)))
                .doesNotThrowAnyException();
    }
}
