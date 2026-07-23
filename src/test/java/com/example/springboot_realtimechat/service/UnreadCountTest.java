package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class UnreadCountTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;

    @Test
    void 가입시_lastRead가_방_최신메시지id로_세팅() {
        Member owner = memberService.create("o@e.com", "1234", "owner");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(owner.getId(), room.getId());
        messageService.create("m1", null, owner.getId(), room.getId(), null);
        var last = messageService.create("m2", null, owner.getId(), room.getId(), null);

        Member joiner = memberService.create("j@e.com", "1234", "joiner");
        ChatRoomMember cm = chatRoomMemberService.join(joiner.getId(), room.getId());

        assertThat(cm.getLastReadMessageId()).isEqualTo(last.getId());
    }

    @Test
    void 안읽음_카운트는_내메시지_삭제_제외하고_lastRead_이후만() {
        Member a = memberService.create("a@e.com", "1234", "a");
        Member b = memberService.create("b@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room");
        chatRoomMemberService.join(a.getId(), room.getId());   // a: lastRead=null(빈 방)
        chatRoomMemberService.join(b.getId(), room.getId());

        // b가 5개 보냄. a 입장에서 5개 안읽음이어야(내것 아님, 삭제 아님)
        for (int i = 0; i < 5; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);
        // a가 1개 보냄 → a의 안읽음엔 안 셈(내 메시지)
        messageService.create("mine", null, a.getId(), room.getId(), null);
        // b의 1개 삭제 → 안읽음에서 빠짐
        var del = messageService.create("del", null, b.getId(), room.getId(), null);
        messageService.delete(del.getId(), b.getId());

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var forRoom = counts.stream().filter(c -> c.getChatroomId().equals(room.getId())).findFirst().orElseThrow();
        assertThat(forRoom.getUnreadCount()).isEqualTo(5L);  // b0~b4
    }
}
