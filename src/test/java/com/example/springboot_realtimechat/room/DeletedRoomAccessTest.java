package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DeletedRoomAccessTest {

    @Autowired RoomAccess roomAccess;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 삭제된_방에서는_멤버십이_인정되지_않는다() {
        Member owner = memberService.create("d-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("삭제방", false, owner.getId());
        assertThat(roomAccess.isMember(owner.getId(), room.getId())).isTrue();

        ReflectionTestUtils.setField(room, "deletedAt", LocalDateTime.now());
        chatRoomRepository.save(room);

        assertThat(roomAccess.isMember(owner.getId(), room.getId())).isFalse();
    }

    @Test
    void 삭제된_방에서도_나갈_수_있다() {
        Member owner = memberService.create("d-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("d-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("삭제방2", false, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        ReflectionTestUtils.setField(room, "deletedAt", LocalDateTime.now());
        chatRoomRepository.save(room);

        assertThatCode(() -> chatRoomMemberService.leave(guest.getId(), room.getId()))
                .doesNotThrowAnyException();
        assertThat(chatRoomMemberRepository.existsByMemberIdAndChatRoomId(guest.getId(), room.getId()))
                .isFalse();
    }

    @Test
    void 방장은_방을_나갈_수_없다() {
        Member owner = memberService.create("d-owner3@e.com", "1234", "주인3");
        ChatRoom room = chatRoomService.create("주인방", false, owner.getId());

        assertThatThrownBy(() -> chatRoomMemberService.leave(owner.getId(), room.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);
    }

    @Test
    void 주인_없는_방은_아무나_나갈_수_있다() {
        Member guest = memberService.create("d-guest4@e.com", "1234", "손님4");
        ChatRoom room = chatRoomService.create("주인없는방", false, null);
        chatRoomMemberService.join(guest.getId(), room.getId(), null);

        assertThatCode(() -> chatRoomMemberService.leave(guest.getId(), room.getId()))
                .doesNotThrowAnyException();
    }
}
