package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ChatRoomMemberIdsQueryTest {

    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;

    @Test
    void 방_멤버의_id만_돌려준다() {
        ChatRoom room = chatRoomService.create("멤버id방", false, null);
        ChatRoom other = chatRoomService.create("다른방", false, null);
        Member a = memberService.create("ids-a@e.com", "1234", "a");
        Member b = memberService.create("ids-b@e.com", "1234", "b");
        Member outsider = memberService.create("ids-c@e.com", "1234", "c");
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);
        chatRoomMemberService.join(outsider.getId(), other.getId(), null);

        assertThat(chatRoomMemberRepository.findMemberIdsByChatRoomId(room.getId()))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }
}
