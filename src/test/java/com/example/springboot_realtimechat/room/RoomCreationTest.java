package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomCreationTest {

    @Autowired ChatRoomService chatRoomService;
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
    void 생성자는_주인이자_첫_멤버가_된다() {
        Member owner = memberService.create("rc-owner@e.com", "1234", "주인");

        ChatRoom room = chatRoomService.create("새방", false, owner.getId());

        assertThat(room.getCreatedBy().getId()).isEqualTo(owner.getId());
        assertThat(chatRoomMemberRepository.existsByMemberIdAndChatRoomId(owner.getId(), room.getId()))
                .isTrue();
    }

    @Test
    void 비공개방은_코드를_받는다() {
        Member owner = memberService.create("rc-owner2@e.com", "1234", "주인2");

        ChatRoom room = chatRoomService.create("잠긴방", true, owner.getId());

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).hasSize(12);
    }

    @Test
    void 공개방은_코드를_받지_않는다() {
        Member owner = memberService.create("rc-owner3@e.com", "1234", "주인3");

        ChatRoom room = chatRoomService.create("열린방", false, owner.getId());

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
    }

    @Test
    void 삭제된_방은_목록과_단건_조회에서_빠진다() {
        Member owner = memberService.create("rc-owner4@e.com", "1234", "주인4");
        ChatRoom room = chatRoomService.create("지운방", false, owner.getId());
        chatRoomRepository.findById(room.getId()).ifPresent(r ->
                chatRoomRepository.save(markDeleted(r)));

        assertThat(chatRoomService.getAllChatRooms())
                .extracting(ChatRoom::getId)
                .doesNotContain(room.getId());
        assertThat(chatRoomService.getChatRoomByIdIncludingDeleted(room.getId()).getId())
                .isEqualTo(room.getId());
        assertThatThrownBy(() -> chatRoomService.getChatRoomById(room.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    // 삭제 API는 2단계다. 여기서는 리플렉션 대신 리포지토리로 직접 값을 넣는다.
    private ChatRoom markDeleted(ChatRoom room) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                room, "deletedAt", java.time.LocalDateTime.now());
        return room;
    }
}
