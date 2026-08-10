package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomMutationTest {

    @Test
    void 소프트_삭제는_시각을_남긴다() {
        ChatRoom room = ChatRoom.publicRoom("방", null);
        assertThat(room.isDeleted()).isFalse();

        room.softDelete();

        assertThat(room.isDeleted()).isTrue();
        assertThat(room.getDeletedAt()).isNotNull();
    }

    @Test
    void 이미_삭제된_방을_다시_삭제해도_시각이_바뀌지_않는다() {
        ChatRoom room = ChatRoom.publicRoom("방", null);
        room.softDelete();
        var first = room.getDeletedAt();

        room.softDelete();

        assertThat(room.getDeletedAt()).isEqualTo(first);
    }

    @Test
    void 공개로_바꾸면_코드가_사라진다() {
        ChatRoom room = ChatRoom.privateRoom("잠김", null, "ABCDEFGHJKLM");

        room.makePublic();

        assertThat(room.isPrivate()).isFalse();
        assertThat(room.getInviteCode()).isNull();
    }

    @Test
    void 비공개로_바꾸면_코드가_생긴다() {
        ChatRoom room = ChatRoom.publicRoom("열림", null);

        room.makePrivate("MNPQRSTUVWXY");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("MNPQRSTUVWXY");
    }

    @Test
    void 코드_재발급은_코드만_바꾼다() {
        ChatRoom room = ChatRoom.privateRoom("잠김", null, "ABCDEFGHJKLM");

        room.reissueInviteCode("MNPQRSTUVWXY");

        assertThat(room.isPrivate()).isTrue();
        assertThat(room.getInviteCode()).isEqualTo("MNPQRSTUVWXY");
    }
}
