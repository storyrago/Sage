package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 코드는 한 번만 쓸 수 있어야 하고, 교환은 인증 없이 되어야 하며, 발급 경로이므로
// 회원 단위 무효화 키를 지워야 한다(토큰 무효화 설계 D3).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OAuthCodeExchangeTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired OAuthCodeStore codeStore;
    @Autowired TokenDenylist denylist;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.keys("oauth:code:*").forEach(redis::delete);
        redis.keys("jwt:denylist:*").forEach(redis::delete);
    }

    private String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    void 발급된_코드를_교환하면_액세스_토큰이_나온다() throws Exception {
        Member member = memberService.create("exch1@e.com", "1234", "교환1");
        String code = codeStore.issue(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void 교환은_인증_없이_호출할_수_있다() throws Exception {
        Member member = memberService.create("exch2@e.com", "1234", "교환2");
        String code = codeStore.issue(member.getId());

        // Authorization 헤더가 없어도 401이 아니다
        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());
    }

    @Test
    void 같은_코드를_두_번_교환하면_두_번째는_401이다() throws Exception {
        Member member = memberService.create("exch3@e.com", "1234", "교환3");
        String code = codeStore.issue(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 존재하지_않는_코드는_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("no-such-code")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 코드가_가리키는_회원이_없으면_401이다() throws Exception {
        String code = codeStore.issue(99999999L);

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 교환이_회원_단위_무효화_키를_지운다() throws Exception {
        Member member = memberService.create("exch4@e.com", "1234", "교환4");
        denylist.revokeMember(member.getId());
        String code = codeStore.issue(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());

        assertThat(redis.hasKey("jwt:denylist:member:" + member.getId())).isFalse();
    }

    @Test
    void 교환으로_받은_토큰으로_API를_호출할_수_있다() throws Exception {
        Member member = memberService.create("exch5@e.com", "1234", "교환5");
        String code = codeStore.issue(member.getId());

        String response = mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
