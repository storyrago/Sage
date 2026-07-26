package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OAuthPersistenceTest {
    @Autowired MemberRepository memberRepository;

    @Test
    void 구글회원_저장하고_sub로_조회() {
        Member saved = memberRepository.save(
                Member.ofGoogle("g@e.com", "구글이", "http://img", "sub-123"));

        Member found = memberRepository.findByGoogleSub("sub-123").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPassword()).isNull();
        assertThat(found.getProvider()).isEqualTo("GOOGLE");
    }
}
