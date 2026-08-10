package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.BannedMemberResponse;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomBanListTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 차단_목록에_강퇴당한_사람이_보인다() {
        Member owner = memberService.create("b1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("b1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("차단방", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        var banned = chatRoomMemberService.getBannedMembers(room.getId(), owner.getId());

        assertThat(banned).extracting(BannedMemberResponse::memberId).containsExactly(guest.getId());
        assertThat(banned).extracting(BannedMemberResponse::nickname).containsExactly("손님");
    }

    @Test
    void 차단을_해제하면_다시_들어올_수_있다() {
        Member owner = memberService.create("b2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("b2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("차단방2", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        chatRoomMemberService.unban(room.getId(), guest.getId(), owner.getId());

        assertThat(chatRoomBanRepository.existsByChatRoomIdAndMemberId(room.getId(), guest.getId())).isFalse();
        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void 방장이_아니면_차단_목록도_해제도_못_한다() {
        Member owner = memberService.create("b3-owner@e.com", "1234", "주인");
        Member other = memberService.create("b3-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("차단방3", false, owner.getId());
        chatRoomMemberService.join(other.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomMemberService.getBannedMembers(room.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
        assertThatThrownBy(() -> chatRoomMemberService.unban(room.getId(), other.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 차단되지_않은_사람을_해제해도_터지지_않는다() {
        Member owner = memberService.create("b4-owner@e.com", "1234", "주인");
        Member guest = memberService.create("b4-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("차단방4", false, owner.getId());

        assertThatCode(() -> chatRoomMemberService.unban(room.getId(), guest.getId(), owner.getId()))
                .doesNotThrowAnyException();
    }
}
