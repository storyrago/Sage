package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessagePageResponse;
import com.example.springboot_realtimechat.dto.MessageRequest;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chatrooms/{chatroomId}/messages")
public class MessageController {
    private final MessageService messageService;

    @PostMapping
    public MessageResponse sendMessage(
            @PathVariable Long chatroomId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody MessageRequest messageRequest) {
        Message message = messageService.create(
                messageRequest.getContent(),
                messageRequest.getImageUrl(),
                customUserDetails.getMemberId(),
                chatroomId,
                messageRequest.getReplyToId());
        return MessageResponse.from(message);
    }

    @GetMapping
    public MessagePageResponse getMessages(
            @PathVariable Long chatroomId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "30") int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        MessageService.MessagePage page = messageService.getMessages(
                chatroomId, customUserDetails.getMemberId(), before, capped);
        List<MessageResponse> messages = page.messages().stream()
                .map(MessageResponse::from)
                .toList();
        return new MessagePageResponse(messages, page.hasMore());
    }
}
