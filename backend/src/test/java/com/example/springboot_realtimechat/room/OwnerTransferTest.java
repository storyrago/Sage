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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OwnerTransferTest {

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
    void 주인이_멤버에게_방장을_넘긴다() {
        Member owner = memberService.create("t1-owner@e.com", "1234", "주인");
        Member next = memberService.create("t1-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방", false, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), null);

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.isOwnedBy(next.getId())).isTrue();
        assertThat(reloaded.isOwnedBy(owner.getId())).isFalse();
    }

    @Test
    void 위임하면_옛_주인이_방을_나갈_수_있다() {
        Member owner = memberService.create("t2-owner@e.com", "1234", "주인");
        Member next = memberService.create("t2-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방2", false, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), null);

        // 위임 전에는 막힌다
        assertThatThrownBy(() -> chatRoomMemberService.leave(owner.getId(), room.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        assertThatCode(() -> chatRoomMemberService.leave(owner.getId(), room.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void 위임하면_방장_권한이_실제로_옮겨간다() {
        Member owner = memberService.create("t3-owner@e.com", "1234", "주인");
        Member next = memberService.create("t3-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방3", true, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), room.getInviteCode());

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        // 새 주인은 방장 API를 쓸 수 있다
        assertThatCode(() -> chatRoomService.reissueInviteCode(room.getId(), next.getId()))
                .doesNotThrowAnyException();
        // 옛 주인은 못 쓴다
        assertThatThrownBy(() -> chatRoomService.reissueInviteCode(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 주인이_아니면_위임할_수_없다() {
        Member owner = memberService.create("t4-owner@e.com", "1234", "주인");
        Member a = memberService.create("t4-a@e.com", "1234", "에이");
        Member b = memberService.create("t4-b@e.com", "1234", "비");
        ChatRoom room = chatRoomService.create("위임방4", false, owner.getId());
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), b.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 주인_없는_방은_아무도_위임할_수_없다() {
        Member a = memberService.create("t5-a@e.com", "1234", "에이");
        Member b = memberService.create("t5-b@e.com", "1234", "비");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), b.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 멤버가_아닌_사람에게는_위임할_수_없다() {
        Member owner = memberService.create("t6-owner@e.com", "1234", "주인");
        Member outsider = memberService.create("t6-out@e.com", "1234", "밖");
        ChatRoom room = chatRoomService.create("위임방6", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), outsider.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 자기_자신에게는_위임할_수_없다() {
        Member owner = memberService.create("t7-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("위임방7", false, owner.getId());

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), owner.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 삭제된_방은_위임할_수_없다() {
        Member owner = memberService.create("t8-owner@e.com", "1234", "주인");
        Member next = memberService.create("t8-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("삭제된방", false, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), null);
        chatRoomService.delete(room.getId(), owner.getId());

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 잠긴_방을_위임하면_잠금은_유지되고_코드는_바뀐다() {
        Member owner = memberService.create("t9-owner@e.com", "1234", "주인");
        Member next = memberService.create("t9-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("잠긴위임방", true, owner.getId());
        String code = room.getInviteCode();
        chatRoomMemberService.join(next.getId(), room.getId(), code);

        chatRoomService.transferOwnership(room.getId(), next.getId(), owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.isPrivate()).isTrue();
        assertThat(reloaded.getInviteCode()).isNotEqualTo(code);
    }

    @Test
    void 주인없는_방의_멤버가_자기_자신에게_위임하려면_NOT_ROOM_OWNER가_나온다() {
        Member a = memberService.create("t10-a@e.com", "1234", "에이");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.transferOwnership(room.getId(), a.getId(), a.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }
}
