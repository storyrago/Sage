package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InviteCodeReissueTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 재발급하면_코드가_바뀌고_옛_코드는_안_통한다() {
        Member owner = memberService.create("r1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("r1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("재발급방", true, owner.getId());
        String oldCode = room.getInviteCode();

        ChatRoom reissued = chatRoomService.reissueInviteCode(room.getId(), owner.getId());

        assertThat(reissued.getInviteCode()).hasSize(12).isNotEqualTo(oldCode);
        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), oldCode))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 재발급된_새_코드로는_들어올_수_있다() {
        Member owner = memberService.create("r2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("r2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("재발급방2", true, owner.getId());

        String newCode = chatRoomService.reissueInviteCode(room.getId(), owner.getId()).getInviteCode();

        assertThat(chatRoomMemberService.join(guest.getId(), room.getId(), newCode)).isNotNull();
    }

    @Test
    void 방장이_아니면_재발급할_수_없다() {
        Member owner = memberService.create("r3-owner@e.com", "1234", "주인");
        Member other = memberService.create("r3-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("재발급방3", true, owner.getId());

        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 공개방은_재발급할_수_없다() {
        Member owner = memberService.create("r4-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("공개방", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_LOCKED);
    }

    @Test
    void 삭제된_방은_재발급할_수_없다() {
        Member owner = memberService.create("r5-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제후재발급", true, owner.getId());
        chatRoomService.delete(room.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }
}
