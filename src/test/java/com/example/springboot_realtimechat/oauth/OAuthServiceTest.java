package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.OAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OAuthServiceTest {
    @Autowired OAuthService oAuthService;
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;

    @Test
    void 신규_구글사용자_생성() {
        Member m = oAuthService.upsertGoogleUser("sub-1", "new@g.com", true, "Alexander Longname", "http://p");
        assertThat(m.getId()).isNotNull();
        assertThat(m.getPassword()).isNull();
        assertThat(m.getProvider()).isEqualTo("GOOGLE");
        assertThat(m.getGoogleSub()).isEqualTo("sub-1");
        assertThat(m.getNickname()).isEqualTo("Alexander");   // 10자 절단
    }

    @Test
    void 같은_sub이면_이메일_달라도_동일회원() {
        Member first = oAuthService.upsertGoogleUser("sub-2", "a@g.com", true, "밥", "http://p");
        Member again = oAuthService.upsertGoogleUser("sub-2", "changed@g.com", true, "밥", "http://p");
        assertThat(again.getId()).isEqualTo(first.getId());   // sub로 동일인
        assertThat(again.getEmail()).isEqualTo("changed@g.com"); // 이메일 동기화
    }

    @Test
    void 검증된_이메일이_기존LOCAL회원과_같으면_연결() {
        Member local = memberService.create("link@g.com", "pw1234", "로컬");
        Member linked = oAuthService.upsertGoogleUser("sub-3", "link@g.com", true, "로컬", "http://p");
        assertThat(linked.getId()).isEqualTo(local.getId());  // 같은 계정에 연결
        assertThat(linked.getGoogleSub()).isEqualTo("sub-3");
        assertThat(memberRepository.count()).isEqualTo(1);    // 새 회원 안 생김
    }

    @Test
    void 미검증_이메일이_기존회원과_충돌하면_거부() {
        memberService.create("dup@g.com", "pw1234", "로컬");
        assertThatThrownBy(() -> oAuthService.upsertGoogleUser("sub-4", "dup@g.com", false, "누구", "http://p"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
}
