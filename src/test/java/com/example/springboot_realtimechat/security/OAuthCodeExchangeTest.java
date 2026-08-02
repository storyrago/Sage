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

import java.util.ArrayList;
import java.util.List;

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

    // 이 인스턴스의 모든 oauth:code:*·jwt:denylist:* 키를 지우면, 개발자가 로컬 앱을 띄운 채
    // 테스트를 돌릴 때 그 세션의 진행 중인 로그인·거부목록까지 함께 지워진다.
    // 이 테스트가 실제로 만든 코드·회원의 키만 지운다.
    private final List<String> issuedCodes = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        issuedCodes.forEach(code -> redis.delete("oauth:code:" + code));
        createdMemberIds.forEach(id -> redis.delete("jwt:denylist:member:" + id));
    }

    private Member createMember(String email, String password, String nickname) {
        Member member = memberService.create(email, password, nickname);
        createdMemberIds.add(member.getId());
        return member;
    }

    private String issueCode(Long memberId) {
        String code = codeStore.issue(memberId);
        issuedCodes.add(code);
        return code;
    }

    private String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    void 발급된_코드를_교환하면_액세스_토큰이_나온다() throws Exception {
        Member member = createMember("exch1@e.com", "1234", "교환1");
        String code = issueCode(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void 교환은_인증_없이_호출할_수_있다() throws Exception {
        Member member = createMember("exch2@e.com", "1234", "교환2");
        String code = issueCode(member.getId());

        // Authorization 헤더가 없어도 401이 아니다
        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());
    }

    @Test
    void 같은_코드를_두_번_교환하면_두_번째는_401이다() throws Exception {
        Member member = createMember("exch3@e.com", "1234", "교환3");
        String code = issueCode(member.getId());

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
    void 빈_코드는_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest());
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
        String code = issueCode(99999999L);

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 교환이_회원_단위_무효화_키를_지운다() throws Exception {
        Member member = createMember("exch4@e.com", "1234", "교환4");
        denylist.revokeMember(member.getId());
        String code = issueCode(member.getId());

        mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andExpect(status().isOk());

        assertThat(redis.hasKey("jwt:denylist:member:" + member.getId())).isFalse();
    }

    @Test
    void 교환으로_받은_토큰으로_API를_호출할_수_있다() throws Exception {
        Member member = createMember("exch5@e.com", "1234", "교환5");
        String code = issueCode(member.getId());

        String response = mockMvc.perform(post("/api/auth/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(code)))
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
