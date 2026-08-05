package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.ChatRoomResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomResponseTest {

    private Member member(Long id) {
        Member m = new Member("a@e.com", "pw", "닉");
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    @Test
    void 주인에게만_초대_코드가_실린다() {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");

        assertThat(ChatRoomResponse.from(room, 1L, true).getInviteCode()).isEqualTo("ABCDEFGHJKLM");
        assertThat(ChatRoomResponse.from(room, 2L, true).getInviteCode()).isNull();
        assertThat(ChatRoomResponse.from(room, 2L, false).getInviteCode()).isNull();
    }

    @Test
    void 주인_없는_방은_아무도_주인이_아니다() {
        ChatRoom room = ChatRoom.publicRoom("시드방", null);

        ChatRoomResponse response = ChatRoomResponse.from(room, 1L, false);

        assertThat(response.isOwner()).isFalse();
        assertThat(response.getInviteCode()).isNull();
    }

    @Test
    void 잠금과_참여_여부가_실린다() {
        Member owner = member(1L);
        ChatRoom locked = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");
        ChatRoom open = ChatRoom.publicRoom("열림", owner);

        assertThat(ChatRoomResponse.from(locked, 2L, false).isLocked()).isTrue();
        assertThat(ChatRoomResponse.from(open, 2L, true).isLocked()).isFalse();
        assertThat(ChatRoomResponse.from(open, 2L, true).isJoined()).isTrue();
    }
}
