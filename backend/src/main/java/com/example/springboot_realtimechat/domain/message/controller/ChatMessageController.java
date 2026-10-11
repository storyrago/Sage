package com.example.springboot_realtimechat.domain.message.controller;

import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.presence.dto.TypingRequest;
import com.example.springboot_realtimechat.domain.presence.dto.TypingResponse;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/** STOMP로 받는 것은 입력 중 신호뿐이다. 메시지는 REST(MessageController)로 보낸다. */
@Controller
@RequiredArgsConstructor
public class ChatMessageController {
    private final MemberService memberService;
    private final RedisPublisher redisPublisher;

    @MessageMapping("/chatrooms/{chatroomId}/typing")
    public void typing(
            @DestinationVariable Long chatroomId,
            TypingRequest typingRequest,
            Principal principal) {
        CustomUserDetails customUserDetails = (CustomUserDetails) ((Authentication) principal).getPrincipal();
        Member member = memberService.getMemberById(customUserDetails.getMemberId());
        TypingResponse response = new TypingResponse(
                chatroomId, member.getId(), member.getNickname(), typingRequest.isTyping());
        redisPublisher.publishTyping(response);
    }
}
