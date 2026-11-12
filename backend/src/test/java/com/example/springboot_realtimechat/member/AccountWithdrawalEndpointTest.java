package com.example.springboot_realtimechat.member;

import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 커밋되는 테스트다. 탈퇴 후 토큰 무효화가 AFTER_COMMIT 리스너로 돈다.
@SpringBootTest
@AutoConfigureMockMvc
class AccountWithdrawalEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired StringRedisTemplate redis;

    @MockitoBean S3Service s3Service;

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
        memberRepository.deleteAll();
    }

    @Test
    void 내_계정_삭제는_204를_주고_회원을_지운다() throws Exception {
        Member member = memberService.create("withdraw-endpoint@e.com", "1234", "탈퇴자");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(delete("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(memberRepository.findById(member.getId())).isEmpty();
    }

    @Test
    void 토큰_없이_내_계정_삭제는_401이다() throws Exception {
        mockMvc.perform(delete("/api/members/me"))
                .andExpect(status().isUnauthorized());
    }
}
