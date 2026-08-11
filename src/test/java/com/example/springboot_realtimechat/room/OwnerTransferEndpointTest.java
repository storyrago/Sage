package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.JwtTokenProvider;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// RoomPrivacyEndpointTest와 같은 이유로 서비스 직접 호출 테스트만으로는 부족하다:
// 컨트롤러가 ChatRoomResponse.from에 넘기는 요청자 인자가 뒤바뀌어도(예: 옛 주인 대신
// 새 주인 id로 응답을 조립) 서비스 테스트는 그 오조합을 잡지 못한다. HTTP 계층에서
// 실제 직렬화된 응답을 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
class OwnerTransferEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private String tokenFor(Member member) {
        return jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
    }

    @Test
    void 정상_위임은_200과_옛_주인_기준_응답을_받는다() throws Exception {
        Member owner = memberService.create("oe1-owner@e.com", "1234", "주인");
        Member next = memberService.create("oe1-next@e.com", "1234", "후계자");
        ChatRoom room = chatRoomService.create("위임방", true, owner.getId());
        chatRoomMemberService.join(next.getId(), room.getId(), room.getInviteCode());

        mockMvc.perform(patch("/api/chatrooms/" + room.getId() + "/owner")
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": " + next.getId() + "}"))
                .andExpect(status().isOk())
                // 응답은 요청자(옛 주인) 기준이어야 한다 — 위임 직후 요청자는 더 이상 주인이 아니다.
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.inviteCode").doesNotExist());
    }

    @Test
    void 본문_없는_PATCH는_400이다() throws Exception {
        Member owner = memberService.create("oe2-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("위임방2", false, owner.getId());

        mockMvc.perform(patch("/api/chatrooms/" + room.getId() + "/owner")
                        .header("Authorization", "Bearer " + tokenFor(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void memberId가_빠진_PATCH도_400이다() throws Exception {
        Member owner = memberService.create("oe3-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("위임방3", false, owner.getId());

        mockMvc.perform(patch("/api/chatrooms/" + room.getId() + "/owner")
                        .header("Authorization", "Bearer " + tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 주인이_아니면_403이다() throws Exception {
        Member owner = memberService.create("oe4-owner@e.com", "1234", "주인");
        Member other = memberService.create("oe4-other@e.com", "1234", "다른사람");
        Member target = memberService.create("oe4-target@e.com", "1234", "대상");
        ChatRoom room = chatRoomService.create("위임방4", false, owner.getId());
        chatRoomMemberService.join(other.getId(), room.getId(), null);
        chatRoomMemberService.join(target.getId(), room.getId(), null);

        mockMvc.perform(patch("/api/chatrooms/" + room.getId() + "/owner")
                        .header("Authorization", "Bearer " + tokenFor(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": " + target.getId() + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ROOM_OWNER"));
    }
}
