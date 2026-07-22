package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.ChatRoomMemberResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class ChatRoomMemberN1Test {
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;

    @Test
    void 참가자_목록에_닉네임_포함_페치조인() {
        Member a = memberService.create("a@e.com", "1234", "앨리스");
        Member b = memberService.create("b@e.com", "1234", "밥");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(a.getId(), room.getId());
        chatRoomMemberService.join(b.getId(), room.getId());

        List<ChatRoomMember> members = chatRoomMemberService.getChatRoomMembersById(room.getId());
        assertThat(members).hasSize(2);

        List<ChatRoomMemberResponse> res = members.stream().map(ChatRoomMemberResponse::from).toList();
        assertThat(res).extracting(ChatRoomMemberResponse::getNickname)
                .containsExactlyInAnyOrder("앨리스", "밥");
        assertThat(res).extracting(ChatRoomMemberResponse::getMemberId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }
}
