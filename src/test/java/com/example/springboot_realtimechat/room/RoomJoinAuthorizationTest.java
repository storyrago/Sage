package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomBan;
import com.example.springboot_realtimechat.domain.Member;
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
class RoomJoinAuthorizationTest {

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
    void 공개방은_코드_없이_들어간다() {
        Member owner = memberService.create("j-owner@e.com", "1234", "주인");
        Member guest = memberService.create("j-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("공개", false, owner.getId());

        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void 잠긴_방은_코드가_맞아야_들어간다() {
        Member owner = memberService.create("j-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("j-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("잠김", true, owner.getId());

        assertThatCode(() -> chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode()))
                .doesNotThrowAnyException();
    }

    @Test
    void 코드가_없으면_잠긴_방에_못_들어간다() {
        Member owner = memberService.create("j-owner3@e.com", "1234", "주인3");
        Member guest = memberService.create("j-guest3@e.com", "1234", "손님3");
        ChatRoom room = chatRoomService.create("잠김3", true, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 코드가_틀리면_못_들어간다() {
        Member owner = memberService.create("j-owner4@e.com", "1234", "주인4");
        Member guest = memberService.create("j-guest4@e.com", "1234", "손님4");
        ChatRoom room = chatRoomService.create("잠김4", true, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), "AAAAAAAAAAAA"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 동결된_방은_아무도_못_들어간다() {
        Member owner = memberService.create("j-owner5@e.com", "1234", "주인5");
        Member guest = memberService.create("j-guest5@e.com", "1234", "손님5");
        ChatRoom room = chatRoomService.create("동결", true, owner.getId());
        // 레거시 동결 데이터와 같은 상태를 만든다: 잠겨 있지만 코드가 없다.
        org.springframework.test.util.ReflectionTestUtils.setField(room, "inviteCode", null);
        chatRoomRepository.save(room);

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), "AAAAAAAAAAAA"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    void 차단된_사람은_코드를_알아도_못_들어간다() {
        Member owner = memberService.create("j-owner6@e.com", "1234", "주인6");
        Member banned = memberService.create("j-banned@e.com", "1234", "차단됨");
        ChatRoom room = chatRoomService.create("차단방", true, owner.getId());
        chatRoomBanRepository.save(new ChatRoomBan(room.getId(), banned.getId()));

        assertThatThrownBy(() -> chatRoomMemberService.join(banned.getId(), room.getId(), room.getInviteCode()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }

    @Test
    void 차단_검사가_중복_참여_검사보다_먼저다() {
        Member owner = memberService.create("j-owner7@e.com", "1234", "주인7");
        ChatRoom room = chatRoomService.create("순서방", false, owner.getId());
        chatRoomBanRepository.save(new ChatRoomBan(room.getId(), owner.getId()));

        assertThatThrownBy(() -> chatRoomMemberService.join(owner.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }
}
