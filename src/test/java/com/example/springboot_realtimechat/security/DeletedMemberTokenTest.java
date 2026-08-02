package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 탈퇴는 AFTER_COMMIT 이벤트로 무효화한다. @Transactional을 붙이면 커밋되지 않아
// 리스너가 돌지 않는다 — 이 테스트는 커밋되는 테스트여야 한다.
@SpringBootTest
@AutoConfigureMockMvc
class DeletedMemberTokenTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JwtAuthChannelInterceptor interceptor;
    @Autowired StringRedisTemplate redis;

    @MockitoBean S3Service s3Service;

    private final MessageChannel channel = mock(MessageChannel.class);

    @AfterEach
    void cleanup() {
        redis.keys("jwt:denylist:*").forEach(redis::delete);
        memberRepository.deleteAll();
    }

    private StompHeaderAccessor connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> sent = interceptor.preSend(message, channel);
        return MessageHeaderAccessor.getAccessor(sent, StompHeaderAccessor.class);
    }

    @Test
    void 탈퇴한_회원의_토큰으로는_API를_호출할_수_없다() throws Exception {
        Member member = memberService.create("deleted-token@e.com", "1234", "탈퇴자");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        Thread.sleep(1_100L);   // iat는 초 단위다. 무효화 시각이 발급 시각보다 확실히 뒤여야 한다

        memberService.delete(member.getId());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴한_회원의_토큰은_CONNECT도_통과하지_못한다() throws Exception {
        Member member = memberService.create("deleted-ws@e.com", "1234", "탈퇴자2");
        String token = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        Thread.sleep(1_100L);

        memberService.delete(member.getId());

        assertThat(connect(token).getUser()).isNull();
    }

    @Test
    void 탈퇴는_그_회원의_토큰만_무효화한다() throws Exception {
        Member leaving = memberService.create("deleted-a@e.com", "1234", "탈퇴자3");
        Member staying = memberService.create("deleted-b@e.com", "1234", "남는이");
        String stayingToken = jwtTokenProvider.createAccessToken(staying.getId(), staying.getEmail());
        Thread.sleep(1_100L);

        memberService.delete(leaving.getId());

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + stayingToken))
                .andExpect(status().isOk());
    }
}
