package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomEntityTest {

    private Member member(Long id) {
        Member m = new Member("a@e.com", "pw", "닉");
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    @Test
    void 공개방은_잠기지_않고_코드가_없다() {
        ChatRoom room = ChatRoom.publicRoom("공개방", null);

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
        assertThat(room.getOwner()).isNull();
        assertThat(room.getDeletedAt()).isNull();
    }

    @Test
    void 비공개방은_잠기고_코드를_가진다() {
        ChatRoom room = ChatRoom.privateRoom("비공개방", null, "ABCDEFGHJKLM");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("ABCDEFGHJKLM");
    }

    @Test
    void 주인이면_true() {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.publicRoom("방", owner);

        assertThat(room.isOwnedBy(1L)).isTrue();
    }

    @Test
    void 다른_사람이면_false() {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.publicRoom("방", owner);

        assertThat(room.isOwnedBy(2L)).isFalse();
    }

    @Test
    void 주인_없는_방은_false() {
        ChatRoom room = ChatRoom.publicRoom("주인없는방", null);

        assertThat(room.isOwnedBy(1L)).isFalse();
    }

    @Test
    void memberId가_null이면_false() {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.publicRoom("방", owner);

        assertThat(room.isOwnedBy(null)).isFalse();
    }
}
