package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.Member;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 본인(/me) 조회 응답엔 이메일이 실리는 것을 직렬화 결과로 고정한다. */
class MemberResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 직렬화_결과에_이메일이_있다() throws Exception {
        Member member = new Member("me@test.com", "1234", "닉");

        String json = objectMapper.writeValueAsString(MemberResponse.from(member));

        assertThat(json).contains("\"email\"");
        assertThat(json).contains("me@test.com");
    }
}
