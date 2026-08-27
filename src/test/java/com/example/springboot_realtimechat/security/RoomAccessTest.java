package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoomMember;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.RoomAccess;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RoomAccessTest {

    @Autowired RoomAccess roomAccess;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;

    Member joined;
    Member outsider;
    ChatRoom room;

    @BeforeEach
    void setUp() {
        joined = memberRepository.save(new Member("joined@test.com", null, "참여자"));
        outsider = memberRepository.save(new Member("outsider@test.com", null, "비참여자"));
        room = chatRoomRepository.save(ChatRoom.publicRoom("방", null));
        chatRoomMemberRepository.save(new ChatRoomMember(joined, room));
    }

    @Test
    void 참여한_방이면_true() {
        assertThat(roomAccess.isMember(joined.getId(), room.getId())).isTrue();
    }

    @Test
    void 참여하지_않은_방이면_false() {
        assertThat(roomAccess.isMember(outsider.getId(), room.getId())).isFalse();
    }

    @Test
    void 없는_방이면_false() {
        assertThat(roomAccess.isMember(joined.getId(), 999999L)).isFalse();
    }

    @Test
    void 없는_회원이면_false() {
        assertThat(roomAccess.isMember(999999L, room.getId())).isFalse();
    }

    @Test
    void null_인자는_false() {
        assertThat(roomAccess.isMember(null, room.getId())).isFalse();
        assertThat(roomAccess.isMember(joined.getId(), null)).isFalse();
        assertThat(roomAccess.isMember(null, null)).isFalse();
    }

    @Test
    void null_인자는_조회하지_않는다() {
        ChatRoomMemberRepository repository = Mockito.mock(ChatRoomMemberRepository.class);
        RoomAccess isolated = new RoomAccess(repository);

        assertThat(isolated.isMember(null, 1L)).isFalse();
        assertThat(isolated.isMember(1L, null)).isFalse();

        Mockito.verify(repository, Mockito.never())
                .existsActiveMembership(ArgumentMatchers.any(), ArgumentMatchers.any());
    }
}
