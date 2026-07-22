package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.dto.LoginRequest;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@Transactional
public class LoginRateLimitTest {
    @Autowired AuthService authService;
    @Autowired MemberService memberService;
    @Autowired StringRedisTemplate redis;

    private static final String IP = "203.0.113.7";
    private static final String KEY = "login:fail:" + IP;

    @BeforeEach
    void clean() { redis.delete(KEY); }
    @AfterEach
    void cleanup() { redis.delete(KEY); }

    private LoginRequest req(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    @Test
    void 실패_10회_초과하면_429로_차단() {
        memberService.create("rl@e.com", "correct-pw", "rl");
        LoginRequest wrong = req("rl@e.com", "wrong-pw");

        // 10회 실패 → 각각 INVALID_PASSWORD
        for (int i = 0; i < 10; i++) {
            CustomException ex = catchThrowableOfType(CustomException.class, () -> authService.login(wrong, IP));
            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PASSWORD);
        }

        // 11번째 → 비밀번호 검사 전에 차단
        CustomException blocked = catchThrowableOfType(CustomException.class, () -> authService.login(wrong, IP));
        assertThat(blocked).isNotNull();
        assertThat(blocked.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
    }

    @Test
    void 로그인_성공하면_카운터_리셋() {
        memberService.create("rl2@e.com", "correct-pw", "rl2");
        LoginRequest wrong = req("rl2@e.com", "wrong-pw");
        LoginRequest right = req("rl2@e.com", "correct-pw");

        // 실패 5회 쌓고
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(wrong, IP)).isInstanceOf(CustomException.class);
        }
        // 성공 → 리셋
        authService.login(right, IP);
        assertThat(redis.opsForValue().get(KEY)).isNull();

        // 리셋 후 다시 5회 실패해도 아직 차단 아님(6번째가 여전히 INVALID_PASSWORD)
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(wrong, IP)).isInstanceOf(CustomException.class);
        }
        CustomException ex = catchThrowableOfType(CustomException.class, () -> authService.login(wrong, IP));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PASSWORD); // 아직 임계치 미만
    }
}
