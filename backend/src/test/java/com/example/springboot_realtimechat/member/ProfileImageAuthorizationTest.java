package com.example.springboot_realtimechat.member;

import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 아바타로 지정할 수 있는 것은 본인이 프로필 용도로 올린 이미지뿐이다.
// 남의 키를 지정할 수 있으면 그 사람이 사진을 지워도 참조가 남아 공개 URL이 계속 살아 있는다.
@SpringBootTest
@AutoConfigureMockMvc
class ProfileImageAuthorizationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;

    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    @AfterEach
    void tearDown() {
        memberRepository.deleteAll();
    }

    private String tokenFor(Member member) {
        return jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
    }

    private static String uploadedProfileUrl(Long ownerId) {
        return BUCKET_PREFIX + "profiles/" + ownerId + "/" + UUID.randomUUID() + "_a.png";
    }

    private void patchProfileImage(Member requester, String imageUrl, int expectedStatus) throws Exception {
        mockMvc.perform(patch("/api/members/me/profile-image")
                        .header("Authorization", "Bearer " + tokenFor(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"" + imageUrl + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void 본인이_올린_프로필_키는_200이다() throws Exception {
        Member member = memberService.create("pi-own@e.com", "1234", "본인");
        String url = uploadedProfileUrl(member.getId());

        mockMvc.perform(patch("/api/members/me/profile-image")
                        .header("Authorization", "Bearer " + tokenFor(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"" + url + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(url));
    }

    @Test
    void 남의_프로필_키는_400이다() throws Exception {
        Member attacker = memberService.create("pi-attacker@e.com", "1234", "공격자");
        Member victim = memberService.create("pi-victim@e.com", "1234", "피해자");

        patchProfileImage(attacker, uploadedProfileUrl(victim.getId()), 400);
    }

    // 컬럼 한계를 넘는 값이 저장 단계까지 가면 400이 아닌 응답이 된다.
    // 실제로 걸러내는 것은 키 문법(파일명 상한)이고, DTO의 @Size는 그 뒤를 받치는 이중 방어다.
    @Test
    void 컬럼_한계를_넘는_주소는_400이다() throws Exception {
        Member member = memberService.create("pi-long@e.com", "1234", "긴주소");
        String tooLong = BUCKET_PREFIX + "profiles/" + member.getId() + "/"
                + UUID.randomUUID() + "_" + "a".repeat(600) + ".png";

        patchProfileImage(member, tooLong, 400);
    }

    @Test
    void 외부_URL은_400이다() throws Exception {
        Member member = memberService.create("pi-ext@e.com", "1234", "외부");

        patchProfileImage(member, "https://example.com/x.png", 400);
    }

    @Test
    void 본인_채팅_키는_프로필로_쓸_수_없어_400이다() throws Exception {
        Member member = memberService.create("pi-chat@e.com", "1234", "채팅");
        String chatUrl = BUCKET_PREFIX + "rooms/" + member.getId() + "/" + UUID.randomUUID() + "_a.png";

        patchProfileImage(member, chatUrl, 400);
    }
}
