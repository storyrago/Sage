package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.chatroom.dto.ChatRoomResponse;
import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.member.entity.Member;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void 직렬화_결과에_주인의_초대_코드가_값과_함께_실린다() throws Exception {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");

        String json = objectMapper.writeValueAsString(ChatRoomResponse.from(room, 1L, true));

        assertThat(json).contains("\"inviteCode\":\"ABCDEFGHJKLM\"");
    }

    @Test
    void 직렬화_결과에_비주인은_inviteCode_키_자체가_없다() throws Exception {
        Member owner = member(1L);
        ChatRoom room = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");

        String json = objectMapper.writeValueAsString(ChatRoomResponse.from(room, 2L, false));

        assertThat(json).doesNotContain("inviteCode");
    }

    @Test
    void 잠긴_방_주인은_코드_키가_실리고_공개방_주인은_코드가_없어_키가_빠진다() throws Exception {
        Member owner = member(1L);
        ChatRoom locked = ChatRoom.privateRoom("잠김", owner, "ABCDEFGHJKLM");
        ChatRoom open = ChatRoom.publicRoom("열림", owner);

        String lockedOwnerJson = objectMapper.writeValueAsString(ChatRoomResponse.from(locked, 1L, true));
        String openOwnerJson = objectMapper.writeValueAsString(ChatRoomResponse.from(open, 1L, true));

        assertThat(lockedOwnerJson).contains("\"inviteCode\"");
        assertThat(openOwnerJson).doesNotContain("inviteCode");
    }
}
