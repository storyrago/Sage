package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageRequest;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class ChatMessageController {
    private final MessageService messageService;

    @MessageMapping("/chatrooms/{chatroomId}/messages")
    @SendTo("/sub/chatrooms/{chatroomId}")
    public MessageResponse sendMessage(
            @DestinationVariable Long chatroomId,
            MessageRequest messageRequest,
            Principal principal) {
        CustomUserDetails customUserDetails = (CustomUserDetails) ((Authentication) principal).getPrincipal();

        Message message = messageService.create(
                messageRequest.getContent(),
                customUserDetails.getMemberId(),
                chatroomId);

        return MessageResponse.from(message);
    }
}
