package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /read의 본문은 선택이다. 본문 없음·순번 지정·잘못된 순번을 HTTP 계층에서 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
class ReadEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    private Member reader;
    private ChatRoom room;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    // reader가 들어온 뒤 writer가 3개를 보낸 방(seq 1~3, reader는 0까지 읽음).
    private void roomWithThreeUnread(String prefix) {
        reader = memberService.create(prefix + "-r@e.com", "1234", "reader");
        Member writer = memberService.create(prefix + "-w@e.com", "1234", "writer");
        room = chatRoomService.create(prefix, false, null);
        chatRoomMemberService.join(reader.getId(), room.getId(), null);
        chatRoomMemberService.join(writer.getId(), room.getId(), null);
        for (int i = 0; i < 3; i++) messageService.create("w" + i, null, writer.getId(), room.getId(), null);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(reader.getId(), reader.getEmail());
    }

    @Test
    void 본문이_없으면_최신_순번까지_읽는다() throws Exception {
        roomWithThreeUnread("re1");

        mockMvc.perform(post("/api/chatrooms/" + room.getId() + "/read").header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/chatrooms/unread").header("Authorization", bearer()))
                .andExpect(jsonPath("$[?(@.chatroomId == " + room.getId() + ")].unreadCount").value(0))
                .andExpect(jsonPath("$[?(@.chatroomId == " + room.getId() + ")].lastReadSeq").value(3))
                .andExpect(jsonPath("$[?(@.chatroomId == " + room.getId() + ")].lastMessageSeq").value(3));
    }

    @Test
    void 순번을_주면_그_위치까지_읽는다() throws Exception {
        roomWithThreeUnread("re2");

        mockMvc.perform(post("/api/chatrooms/" + room.getId() + "/read").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/chatrooms/unread").header("Authorization", bearer()))
                .andExpect(jsonPath("$[?(@.chatroomId == " + room.getId() + ")].unreadCount").value(2));
    }

    @Test
    void 음수_순번은_400이다() throws Exception {
        roomWithThreeUnread("re3");

        mockMvc.perform(post("/api/chatrooms/" + room.getId() + "/read").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }
}
