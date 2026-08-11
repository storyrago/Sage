package com.example.springboot_realtimechat.exception;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.JwtTokenProvider;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 클라이언트가 잘못 보낸 요청이 500(스택트레이스)이 아니라 4xx로 끝나는지를 측정/회귀 검증한다.
// GlobalExceptionHandler의 Exception.class 캐치올로 새는 경로를 하나씩 표에 옮긴 것.
@SpringBootTest
@AutoConfigureMockMvc
class MalformedRequestHandlingTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired MemberService memberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private String tokenFor(Member member) {
        return jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
    }

    private String authHeader() {
        Member member = memberService.create("malformed-req@e.com", "1234", "측정용");
        return "Bearer " + tokenFor(member);
    }

    @Test
    void 깨진_JSON_본문은_500이_아니라_400이다() throws Exception {
        mockMvc.perform(post("/api/chatrooms")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 본문이_필요한데_아예_없으면_500이_아니라_400이다() throws Exception {
        mockMvc.perform(post("/api/chatrooms")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 매핑에_없는_HTTP_메서드는_500이_아니라_405다() throws Exception {
        mockMvc.perform(put("/api/chatrooms")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 존재하지_않는_경로는_500이_아니라_404다() throws Exception {
        mockMvc.perform(get("/api/does-not-exist")
                        .header("Authorization", authHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void JSON이_아닌_Content_Type은_500이_아니라_415다() throws Exception {
        mockMvc.perform(post("/api/chatrooms")
                        .header("Authorization", authHeader())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"name\":\"방\",\"private\":false}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void 필수_멀티파트_파트가_없으면_500이_아니라_400이다() throws Exception {
        mockMvc.perform(multipart("/api/images")
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 경로변수_타입_불일치는_이미_400이다() throws Exception {
        mockMvc.perform(post("/api/chatrooms/abc/read")
                        .header("Authorization", authHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }
}
