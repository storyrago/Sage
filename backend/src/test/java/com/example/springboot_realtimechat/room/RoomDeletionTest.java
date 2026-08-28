package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.event.RoomDeletedEvent;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.chatroom.service.RoomAccess;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@RecordApplicationEvents
class RoomDeletionTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
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
    void 방장은_방을_삭제할_수_있다() {
        Member owner = memberService.create("d1-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제방", false, owner.getId());

        chatRoomService.delete(room.getId(), owner.getId());

        assertThat(chatRoomRepository.findById(room.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(chatRoomService.getAllChatRooms())
                .extracting(ChatRoom::getId)
                .doesNotContain(room.getId());
    }

    @Test
    void 방장이_아니면_삭제할_수_없다() {
        Member owner = memberService.create("d2-owner@e.com", "1234", "주인");
        Member other = memberService.create("d2-other@e.com", "1234", "남");
        ChatRoom room = chatRoomService.create("삭제방2", false, owner.getId());
        chatRoomMemberService.join(other.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.delete(room.getId(), other.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 주인_없는_방은_아무도_삭제할_수_없다() {
        Member anyone = memberService.create("d3-any@e.com", "1234", "아무나");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(anyone.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomService.delete(room.getId(), anyone.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_ROOM_OWNER);
    }

    @Test
    void 삭제된_방에서는_멤버십이_인정되지_않고_멤버십_행은_남는다() {
        Member owner = memberService.create("d4-owner@e.com", "1234", "주인");
        Member guest = memberService.create("d4-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("삭제방4", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomService.delete(room.getId(), owner.getId());

        assertThat(roomAccess.isMember(guest.getId(), room.getId())).isFalse();
        assertThat(chatRoomMemberRepository.findChatRoomIdsByMemberId(guest.getId()))
                .doesNotContain(room.getId());
        // 소프트 삭제라 행 자체는 남는다 (owner, guest 두 건 모두)
        assertThat(chatRoomMemberRepository.count()).isEqualTo(2);
    }

    @Test
    void 이미_삭제된_방을_다시_삭제해도_터지지_않는다() {
        Member owner = memberService.create("d5-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제방5", false, owner.getId());
        chatRoomService.delete(room.getId(), owner.getId());

        // 두 번째 호출은 삭제된 방을 찾지 못해 404다
        assertThatThrownBy(() -> chatRoomService.delete(room.getId(), owner.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 삭제된_방에서도_나갈_수_있다() {
        Member owner = memberService.create("d6-owner@e.com", "1234", "주인");
        Member guest = memberService.create("d6-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("삭제방6", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);
        chatRoomService.delete(room.getId(), owner.getId());

        assertThatCode(() -> chatRoomMemberService.leave(guest.getId(), room.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void 삭제하면_RoomDeletedEvent가_멤버_전원_id를_싣고_발행된다() {
        Member owner = memberService.create("d7-owner@e.com", "1234", "주인");
        Member guest = memberService.create("d7-guest@e.com", "1234", "손님");
        ChatRoom room = chatRoomService.create("삭제방7", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        chatRoomService.delete(room.getId(), owner.getId());

        List<RoomDeletedEvent> published = events.stream(RoomDeletedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).roomId()).isEqualTo(room.getId());
        assertThat(published.get(0).memberIds()).containsExactlyInAnyOrder(owner.getId(), guest.getId());
    }
}
