package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RoomRosterAuthorizationTest {

    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;

    private Member member;
    private Member outsider;
    private Long roomId;

    @BeforeEach
    void setUp() {
        member = memberService.create("roster-member@test.com", "1234", "멤버");
        outsider = memberService.create("roster-outsider@test.com", "1234", "비멤버");
        ChatRoom room = chatRoomService.create("명단방");
        roomId = room.getId();
        chatRoomMemberService.join(member.getId(), roomId);
    }

    @Test
    void 멤버는_참여자_목록을_본다() {
        assertThat(chatRoomMemberService.getChatRoomMembersById(roomId, member.getId()))
                .hasSize(1);
    }

    @Test
    void 비멤버는_참여자_목록을_보지_못한다() {
        assertThatThrownBy(() -> chatRoomMemberService.getChatRoomMembersById(roomId, outsider.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 방을_나가면_참여자_목록을_보지_못한다() {
        chatRoomMemberService.leave(member.getId(), roomId);

        assertThatThrownBy(() -> chatRoomMemberService.getChatRoomMembersById(roomId, member.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }
}
