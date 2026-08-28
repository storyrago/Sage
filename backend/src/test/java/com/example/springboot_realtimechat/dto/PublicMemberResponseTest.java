package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.member.dto.PublicMemberResponse;
import com.example.springboot_realtimechat.domain.member.entity.Member;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 타인 조회 응답에 이메일이 실리지 않는 것을 직렬화 결과로 고정한다. */
class PublicMemberResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 직렬화_결과에_이메일이_없다() throws Exception {
        Member member = new Member("secret@test.com", "1234", "닉");

        String json = objectMapper.writeValueAsString(PublicMemberResponse.from(member));

        assertThat(json).doesNotContain("secret@test.com");
        assertThat(json).doesNotContain("email");
        assertThat(json).contains("닉");
    }
}
