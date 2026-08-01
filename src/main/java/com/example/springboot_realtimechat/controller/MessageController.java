package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessagePageResponse;
import com.example.springboot_realtimechat.dto.MessageRequest;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.dto.MessageUpdateRequest;
import com.example.springboot_realtimechat.redis.RedisPublisher;
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
    private final RedisPublisher redisPublisher;

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

    @PatchMapping("/{messageId}")
    public MessageResponse updateMessage(
            @PathVariable Long chatroomId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody MessageUpdateRequest request) {
        Message message = messageService.update(chatroomId, messageId, customUserDetails.getMemberId(), request.getContent());
        MessageResponse response = MessageResponse.from(message);
        redisPublisher.publish(response); // 수정 결과를 방 전체에 실시간 전파
        return response;
    }

    @DeleteMapping("/{messageId}")
    public MessageResponse deleteMessage(
            @PathVariable Long chatroomId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        Message message = messageService.delete(chatroomId, messageId, customUserDetails.getMemberId());
        MessageResponse response = MessageResponse.from(message);
        redisPublisher.publish(response); // 삭제 상태를 방 전체에 실시간 전파
        return response;
    }
}
