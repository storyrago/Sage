package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.domain.chatroom.event.RoomDeletedEvent;
import com.example.springboot_realtimechat.domain.chatroom.event.RoomLeftEvent;
import com.example.springboot_realtimechat.domain.chatroom.event.SubscriptionRevocationListener;
import com.example.springboot_realtimechat.domain.member.event.MemberDeletedEvent;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.global.websocket.RoomSubscriptionRevoker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @Test
    void 방이_삭제되면_멤버_각각의_구독을_ROOM_DELETED_사유로_회수한다() {
        listener.onRoomDeleted(new RoomDeletedEvent(3L, List.of(10L, 20L)));

        verify(revoker).revokeRoom(10L, 3L, ErrorCode.ROOM_DELETED);
        verify(revoker).revokeRoom(20L, 3L, ErrorCode.ROOM_DELETED);
    }

    @Test
    void 방_삭제_회수_중_한_명이_실패해도_나머지는_회수된다() {
        doThrow(new IllegalStateException("boom"))
                .when(revoker).revokeRoom(10L, 3L, ErrorCode.ROOM_DELETED);

        listener.onRoomDeleted(new RoomDeletedEvent(3L, List.of(10L, 20L)));

        verify(revoker).revokeRoom(20L, 3L, ErrorCode.ROOM_DELETED);
    }

    @Test
    void 방_삭제_이벤트에_멤버가_없어도_터지지_않는다() {
        assertThatCode(() -> listener.onRoomDeleted(new RoomDeletedEvent(3L, List.of())))
                .doesNotThrowAnyException();

        verifyNoInteractions(revoker);
    }
}
