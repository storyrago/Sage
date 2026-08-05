package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomEntityTest {

    @Test
    void 공개방은_잠기지_않고_코드가_없다() {
        ChatRoom room = ChatRoom.publicRoom("공개방", null);

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
        assertThat(room.getCreatedBy()).isNull();
        assertThat(room.getDeletedAt()).isNull();
    }

    @Test
    void 비공개방은_잠기고_코드를_가진다() {
        ChatRoom room = ChatRoom.privateRoom("비공개방", null, "ABCDEFGHJKLM");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("ABCDEFGHJKLM");
    }
}
