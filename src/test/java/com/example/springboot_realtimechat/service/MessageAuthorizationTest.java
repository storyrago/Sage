package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
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
class MessageAuthorizationTest {

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private Member author;
    private Member otherMember;
    private Long roomId;
    private Long otherRoomId;
    private Message message;

    @BeforeEach
    void setUp() {
        author = memberService.create("msg-author@test.com", "1234", "작성자");
        otherMember = memberService.create("msg-other@test.com", "1234", "다른멤버");

        ChatRoom room = chatRoomService.create("대상방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
        chatRoomMemberService.join(otherMember.getId(), roomId, null);

        ChatRoom otherRoom = chatRoomService.create("공격자방", false, null);
        otherRoomId = otherRoom.getId();
        chatRoomMemberService.join(author.getId(), otherRoomId, null);

        message = messageService.create("원본", null, author.getId(), roomId, null);
    }

    @Test
    void 멤버인_작성자는_수정한다() {
        Message updated = messageService.update(roomId, message.getId(), author.getId(), "고침");

        assertThat(updated.getContent()).isEqualTo("고침");
    }

    @Test
    void 방을_나가면_자기_메시지도_수정하지_못한다() {
        chatRoomMemberService.leave(author.getId(), roomId);

        assertThatThrownBy(() -> messageService.update(roomId, message.getId(), author.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 방을_나가면_자기_메시지도_삭제하지_못한다() {
        chatRoomMemberService.leave(author.getId(), roomId);

        assertThatThrownBy(() -> messageService.delete(roomId, message.getId(), author.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 자기가_속한_방_id를_붙여도_다른_방_메시지는_수정하지_못한다() {
        assertThatThrownBy(() -> messageService.update(otherRoomId, message.getId(), author.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void 존재하지_않는_방_id를_붙여도_거부된다() {
        assertThatThrownBy(() -> messageService.update(999999L, message.getId(), author.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void 작성자가_아닌_멤버는_수정하지_못한다() {
        assertThatThrownBy(() -> messageService.update(roomId, message.getId(), otherMember.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_MESSAGE_OWNER);
    }

    @Test
    void 멤버인_작성자는_삭제한다() {
        Message deleted = messageService.delete(roomId, message.getId(), author.getId());

        assertThat(deleted.isDeleted()).isTrue();
    }

    @Test
    void 방에_속하지_않은_사용자가_남의_메시지를_수정하면_NOT_JOINED_ROOM() {
        Member outsider = memberService.create("msg-outsider@test.com", "1234", "비멤버");

        assertThatThrownBy(() -> messageService.update(roomId, message.getId(), outsider.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }
}
