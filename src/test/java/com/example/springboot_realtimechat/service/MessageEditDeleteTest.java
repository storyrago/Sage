package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@Transactional
public class MessageEditDeleteTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @Test
    void 수정은_작성자만_editedAt_세팅() {
        Member author = memberService.create("a@e.com", "1234", "author");
        Member other = memberService.create("b@e.com", "1234", "other");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(other.getId(), room.getId());
        Message msg = messageService.create("원본", null, author.getId(), room.getId(), null);

        // 남이 수정 → NOT_MESSAGE_OWNER
        CustomException denied = catchThrowableOfType(CustomException.class,
                () -> messageService.update(room.getId(), msg.getId(), other.getId(), "해킹"));
        assertThat(denied.getErrorCode()).isEqualTo(ErrorCode.NOT_MESSAGE_OWNER);

        // 작성자 수정 → content 변경 + editedAt 세팅
        Message edited = messageService.update(room.getId(), msg.getId(), author.getId(), "수정본");
        assertThat(edited.getContent()).isEqualTo("수정본");
        assertThat(edited.getEditedAt()).isNotNull();
    }

    @Test
    void 삭제는_작성자만_소프트삭제() {
        Member author = memberService.create("c@e.com", "1234", "author2");
        Member other = memberService.create("d@e.com", "1234", "other2");
        ChatRoom room = chatRoomService.create("room2");
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(other.getId(), room.getId());
        Message msg = messageService.create("지울 메시지", null, author.getId(), room.getId(), null);

        // 남이 삭제 → NOT_MESSAGE_OWNER
        CustomException denied = catchThrowableOfType(CustomException.class,
                () -> messageService.delete(room.getId(), msg.getId(), other.getId()));
        assertThat(denied.getErrorCode()).isEqualTo(ErrorCode.NOT_MESSAGE_OWNER);

        // 작성자 삭제 → deleted=true, content 비움
        Message deleted = messageService.delete(room.getId(), msg.getId(), author.getId());
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getContent()).isEmpty();
    }
}
