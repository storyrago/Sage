package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.auth.dto.LoginRequest;
import com.example.springboot_realtimechat.domain.auth.dto.LoginResponse;
import com.example.springboot_realtimechat.domain.auth.service.AuthService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.global.jwt.TokenDenylist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 로그아웃은 그 토큰만 죽인다. 다른 기기와 재로그인은 살아 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LogoutTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired AuthService authService;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TokenDenylist denylist;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
    }

    private LoginRequest req(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    @Test
    void 로그아웃은_204를_돌려준다() throws Exception {
        Member member = memberService.create("logout1@e.com", "1234", "로그아웃");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void 로그아웃한_토큰으로는_API를_호출할_수_없다() throws Exception {
        Member member = memberService.create("logout2@e.com", "1234", "로그아웃2");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃해도_같은_회원의_다른_기기_토큰은_유효하다() throws Exception {
        Member member = memberService.create("logout3@e.com", "1234", "로그아웃3");
        String phone = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        String laptop = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + phone))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + laptop))
                .andExpect(status().isOk());
    }

    @Test
    void 미인증_로그아웃은_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jti가_없는_토큰의_로그아웃은_그_회원_전체를_무효화한다() {
        Member member = memberService.create("logout4@e.com", "1234", "구토큰");
        String legacyToken = legacyTokenWithoutJti(member.getId(), member.getEmail());

        authService.logout(legacyToken);

        assertThat(denylist.isRevoked(null, member.getId(), System.currentTimeMillis() - 5_000L)).isTrue();
    }

    @Test
    void 회원_단위_무효화_이후_재로그인한_토큰은_통과한다() throws Exception {
        Member member = memberService.create("logout5@e.com", "1234", "재로그인");
        denylist.revokeMember(member.getId());

        LoginResponse response = authService.login(req("logout5@e.com", "1234"), "203.0.113.9");

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + response.getAccessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인_성공이_회원_단위_키를_지운다() {
        Member member = memberService.create("logout6@e.com", "1234", "키삭제");
        denylist.revokeMember(member.getId());

        authService.login(req("logout6@e.com", "1234"), "203.0.113.10");

        assertThat(redis.hasKey("jwt:denylist:member:" + member.getId())).isFalse();
    }

    // jti 도입 이전에 발급된 토큰을 흉내낸다. 배포 직후 최대 1시간 동안 실제로 존재한다.
    private String legacyTokenWithoutJti(Long memberId, String email) {
        long now = System.currentTimeMillis();
        return io.jsonwebtoken.Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("email", email)
                .issuedAt(new java.util.Date(now))
                .expiration(new java.util.Date(now + 3600000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-key-for-jwt-authentication-1234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }
}
