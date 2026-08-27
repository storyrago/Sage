package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PATCH /api/chatrooms/{id}가 required=true 기본 @RequestBody였다면 본문 없는 요청이
// HttpMessageNotReadableException으로 500을 냈을 지점이다. required=false + 명시적 null 거부로
// 같은 상황이 400이 되는지를 HTTP 계층에서 직접 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
class RoomPrivacyEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatRoomService chatRoomService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private String tokenFor(Member member) {
        return jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
    }

    @Test
    void 본문_없는_PATCH는_500이_아니라_400이다() throws Exception {
        Member owner = memberService.create("pe1-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방", false, owner.getId());

        mockMvc.perform(patch("/api/chatrooms/" + room.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void private_필드가_빠진_PATCH도_400이다() throws Exception {
        Member owner = memberService.create("pe2-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방2", false, owner.getId());

        mockMvc.perform(patch("/api/chatrooms/" + room.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 정상_요청은_200과_바뀐_상태를_받는다() throws Exception {
        Member owner = memberService.create("pe3-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("전환방3", false, owner.getId());

        mockMvc.perform(patch("/api/chatrooms/" + room.getId())
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"private\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.inviteCode").exists());
    }
}
