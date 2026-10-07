package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessageIdempotencyTest {

    private static final String CLIENT_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private Member joinedMember(String email, ChatRoom... rooms) {
        Member member = memberService.create(email, "1234", "재전송");
        for (ChatRoom room : rooms) {
            chatRoomMemberService.join(member.getId(), room.getId(), null);
        }
        return member;
    }

    @Test
    void 같은_clientMessageId로_다시_보내면_처음_메시지를_돌려준다() {
        ChatRoom room = chatRoomService.create("재전송방", false, null);
        Member member = joinedMember("idem-same@e.com", room);

        Message first = messageService.create("hi", null, member.getId(), room.getId(), null, CLIENT_ID);
        Message retry = messageService.create("hi", null, member.getId(), room.getId(), null, CLIENT_ID);

        assertThat(retry.getId()).isEqualTo(first.getId());
        // 재전송은 순번을 다시 쓰지 않는다. 쓰면 순번에 빈칸이 생긴다.
        assertThat(room.getLastMessageSeq()).isEqualTo(1L);
    }

    @Test
    void clientMessageId가_없으면_매번_새로_저장한다() {
        ChatRoom room = chatRoomService.create("구버전방", false, null);
        Member member = joinedMember("idem-null@e.com", room);

        Message first = messageService.create("hi", null, member.getId(), room.getId(), null, null);
        Message second = messageService.create("hi", null, member.getId(), room.getId(), null, null);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getSeq()).isEqualTo(2L);
    }

    @Test
    void 다른_방에_같은_clientMessageId를_쓰면_거부한다() {
        ChatRoom roomA = chatRoomService.create("충돌방A", false, null);
        ChatRoom roomB = chatRoomService.create("충돌방B", false, null);
        Member member = joinedMember("idem-conflict@e.com", roomA, roomB);
        messageService.create("hi", null, member.getId(), roomA.getId(), null, CLIENT_ID);

        assertThatThrownBy(() -> messageService.create("hi", null, member.getId(), roomB.getId(), null, CLIENT_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CLIENT_MESSAGE_ID_CONFLICT);
    }

    @Test
    void 다른_회원은_같은_clientMessageId를_써도_된다() {
        ChatRoom room = chatRoomService.create("공유방", false, null);
        Member alice = joinedMember("idem-alice@e.com", room);
        Member bob = joinedMember("idem-bob@e.com", room);

        Message fromAlice = messageService.create("a", null, alice.getId(), room.getId(), null, CLIENT_ID);
        Message fromBob = messageService.create("b", null, bob.getId(), room.getId(), null, CLIENT_ID);

        assertThat(fromBob.getId()).isNotEqualTo(fromAlice.getId());
    }

    @Test
    void 응답에_clientMessageId가_실린다() {
        ChatRoom room = chatRoomService.create("응답방", false, null);
        Member member = joinedMember("idem-response@e.com", room);

        Message message = messageService.create("hi", null, member.getId(), room.getId(), null, CLIENT_ID);

        assertThat(MessageResponse.from(message).getClientMessageId()).isEqualTo(CLIENT_ID);
    }

    @Test
    void UUID_형식이_아니면_REST에서_400을_돌려준다() throws Exception {
        ChatRoom room = chatRoomService.create("형식방", false, null);
        Member member = joinedMember("idem-format@e.com", room);
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        MessageRequest request = new MessageRequest();
        request.setContent("hi");
        request.setClientMessageId("not-a-uuid");

        mockMvc.perform(post("/api/chatrooms/{chatroomId}/messages", room.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
