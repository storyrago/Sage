package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.RoomAccess;
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
class RoomPrivacyTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired RoomAccess roomAccess;
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
    void 공개로_바꾸면_코드가_사라지고_아무나_들어온다() {
        Member owner = memberService.create("p1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("p1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("전환방", true, owner.getId());

        ChatRoom changed = chatRoomService.setPrivate(room.getId(), false, owner.getId());

        assertThat(changed.isPrivate()).isFalse();
        assertThat(changed.getInviteCode()).isNull();
        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void 비공개로_바꾸면_새_코드가_생긴다() {
        Member owner = memberService.create("p2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("p2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("전환방2", false, owner.getId());

        ChatRoom changed = chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(changed.isPrivate()).isTrue();
        assertThat(changed.getInviteCode()).hasSize(12);
        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 비공개로_바꿔도_기존_멤버는_유지된다() {
        Member owner = memberService.create("p3-owner@e.com", "1234", "주인");
        Member guest = memberService.create("p3-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("전환방3", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(roomAccess.isMember(guest.getId(), room.getId())).isTrue();
    }

    @Test
    void 비공개를_거쳐_다시_비공개로_바꾸면_옛_코드가_부활하지_않는다() {
        Member owner = memberService.create("p4-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방4", true, owner.getId());
        String first = room.getInviteCode();

        chatRoomService.setPrivate(room.getId(), false, owner.getId());
        ChatRoom again = chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(again.getInviteCode()).isNotEqualTo(first);
    }

    @Test
    void 방장이_아니면_전환할_수_없다() {
        Member owner = memberService.create("p5-owner@e.com", "1234", "주인");
        Member other = memberService.create("p5-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("전환방5", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.setPrivate(room.getId(), true, other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 같은_상태로_바꾸면_비공개는_코드가_새로_생긴다() {
        Member owner = memberService.create("p6-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방6", true, owner.getId());
        String before = room.getInviteCode();

        ChatRoom same = chatRoomService.setPrivate(room.getId(), true, owner.getId());

        assertThat(same.isPrivate()).isTrue();
        assertThat(same.getInviteCode()).isNotEqualTo(before);
    }
}
