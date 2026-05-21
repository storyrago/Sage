package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MemberServiceTest {

    @Autowired
    MemberService memberService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void 회원_생성_시_비밀번호를_암호화해서_저장한다() {
        // given
        String rawPassword = "1234";

        // when
        Member member = memberService.create("password-test@email.com", rawPassword, "nick");

        // then
        assertThat(member.getPassword()).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, member.getPassword())).isTrue();
    }
}
