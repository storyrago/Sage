package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.RoomAccess;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RoomAccessRequireMemberTest {

    @Mock ChatRoomMemberRepository chatRoomMemberRepository;
    @InjectMocks RoomAccess roomAccess;

    @Test
    void 멤버면_통과한다() {
        given(chatRoomMemberRepository.existsActiveMembership(1L, 10L)).willReturn(true);

        assertThatCode(() -> roomAccess.requireMember(1L, 10L)).doesNotThrowAnyException();
    }

    @Test
    void 멤버가_아니면_NOT_JOINED_ROOM으로_거부한다() {
        given(chatRoomMemberRepository.existsActiveMembership(1L, 10L)).willReturn(false);

        assertThatThrownBy(() -> roomAccess.requireMember(1L, 10L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 식별자가_없으면_NOT_JOINED_ROOM으로_거부한다() {
        assertThatThrownBy(() -> roomAccess.requireMember(null, 10L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_JOINED_ROOM);
    }
}
