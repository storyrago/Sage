package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageRequest;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.dto.TypingRequest;
import com.example.springboot_realtimechat.dto.TypingResponse;
import com.example.springboot_realtimechat.redis.RedisPublisher;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageResponseFactory;
import com.example.springboot_realtimechat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class ChatMessageController {
    private final MessageService messageService;
    private final MemberService memberService;
    private final RedisPublisher redisPublisher;
    private final MessageResponseFactory messageResponseFactory;

    @MessageMapping("/chatrooms/{chatroomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatroomId,
            MessageRequest messageRequest,
            Principal principal) {
        CustomUserDetails customUserDetails = (CustomUserDetails) ((Authentication) principal).getPrincipal();

        Message message = messageService.create(
                messageRequest.getContent(),
                messageRequest.getImageUrl(),
                customUserDetails.getMemberId(),
                chatroomId,
                messageRequest.getReplyToId());
        MessageResponse messageResponse = messageResponseFactory.of(message);
        redisPublisher.publish(messageResponse);
    }

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
