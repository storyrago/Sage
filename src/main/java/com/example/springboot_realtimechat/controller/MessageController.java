package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.Message;
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
                messageRequest.getContent(), customUserDetails.getMemberId(), chatroomId);
        return MessageResponse.from(message);
    }

    @GetMapping
    public List<MessageResponse> getMessages(@PathVariable Long chatroomId) {
        List<Message> messageList = messageService.getAllChatRoomMessages(chatroomId);
        return messageList.stream()
                .map(MessageResponse::from)
                .toList();
    }
}
