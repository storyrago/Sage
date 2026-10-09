package com.example.springboot_realtimechat.domain.message.controller;

import com.example.springboot_realtimechat.domain.message.dto.MessagePageResponse;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.dto.MessageUpdateRequest;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.domain.message.delivery.MessageBroadcaster;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;

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
    private final MessageBroadcaster messageBroadcaster;
    private final MessageResponseFactory messageResponseFactory;

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
                messageRequest.getReplyToId(),
                messageRequest.getClientMessageId());
        MessageResponse response = messageResponseFactory.of(message);
        messageBroadcaster.broadcast(response); // 새 메시지를 방 전체에 실시간 전파
        return response;
    }

    @GetMapping
    public MessagePageResponse getMessages(
            @PathVariable Long chatroomId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(defaultValue = "30") int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        MessageService.MessagePage page = messageService.getMessages(
                chatroomId, customUserDetails.getMemberId(), before, beforeSeq, afterSeq, capped);
        List<MessageResponse> messages = page.messages().stream()
                .map(messageResponseFactory::of)
                .toList();
        return new MessagePageResponse(messages, page.hasMore());
    }

    @PatchMapping("/{messageId}")
    public MessageResponse updateMessage(
            @PathVariable Long chatroomId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody MessageUpdateRequest request) {
        Message message = messageService.update(chatroomId, messageId, customUserDetails.getMemberId(), request.getContent());
        MessageResponse response = messageResponseFactory.of(message);
        messageBroadcaster.broadcast(response); // 수정 결과를 방 전체에 실시간 전파
        return response;
    }

    @DeleteMapping("/{messageId}")
    public MessageResponse deleteMessage(
            @PathVariable Long chatroomId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        Message message = messageService.delete(chatroomId, messageId, customUserDetails.getMemberId());
        MessageResponse response = messageResponseFactory.of(message);
        messageBroadcaster.broadcast(response); // 삭제 상태를 방 전체에 실시간 전파
        return response;
    }
}
