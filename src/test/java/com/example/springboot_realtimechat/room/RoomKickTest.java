package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.RoomLeftEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@RecordApplicationEvents
class RoomKickTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired ApplicationEvents events;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 강퇴하면_멤버십이_사라지고_차단이_남는다() {
        Member owner = memberService.create("k1-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k1-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("강퇴방", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThat(roomAccess.isMember(guest.getId(), room.getId())).isFalse();
        assertThat(chatRoomBanRepository.existsByChatRoomIdAndMemberId(room.getId(), guest.getId())).isTrue();
    }

    @Test
    void 강퇴당한_사람은_공개방에도_다시_못_들어온다() {
        Member owner = memberService.create("k2-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k2-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("공개강퇴방", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }

    @Test
    void 강퇴당한_사람은_코드를_알아도_못_들어온다() {
        Member owner = memberService.create("k3-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k3-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("잠긴강퇴방", true, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode());
        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_BANNED);
    }

    @Test
    void 방장이_아니면_강퇴할_수_없다() {
        Member owner = memberService.create("k4-owner@e.com", "1234", "주인");
        Member a = memberService.create("k4-a@e.com", "1234", "에이");
        Member b = memberService.create("k4-b@e.com", "1234", "비");
        ChatRoom room = chatRoomService.create("강퇴방4", false, owner.getId());
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), b.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 방장은_자기_자신을_강퇴할_수_없다() {
        Member owner = memberService.create("k5-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("강퇴방5", false, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), owner.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);
    }

    @Test
    void 강퇴하면_RoomLeftEvent가_ROOM_KICKED_사유로_발행된다() {
        Member owner = memberService.create("k7-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k7-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("강퇴방7", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId());

        assertThat(events.stream(RoomLeftEvent.class).toList())
                .containsExactly(new RoomLeftEvent(guest.getId(), room.getId(), ErrorCode.ROOM_KICKED));
    }

    @Test
    void 멤버가_아닌_사람을_강퇴하면_거부된다() {
        Member owner = memberService.create("k6-owner@e.com", "1234", "주인");
        Member outsider = memberService.create("k6-out@e.com", "1234", "밖");
        ChatRoom room = chatRoomService.create("강퇴방6", false, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), outsider.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 삭제된_방에서는_강퇴할_수_없다() {
        Member owner = memberService.create("k8-owner@e.com", "1234", "주인");
        Member guest = memberService.create("k8-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("강퇴방8", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomService.delete(room.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), guest.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 주인_없는_방에서는_강퇴할_수_없다() {
        Member anyone = memberService.create("k9-any@e.com", "1234", "아무나");
        Member guest = memberService.create("k9-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("주인없는강퇴방", false, null);
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomMemberService.kick(room.getId(), guest.getId(), anyone.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }
}
