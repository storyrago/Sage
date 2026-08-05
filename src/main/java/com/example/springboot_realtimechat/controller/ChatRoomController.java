package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.dto.ChatRoomRequest;
import com.example.springboot_realtimechat.dto.ChatRoomResponse;
import com.example.springboot_realtimechat.dto.UnreadCountResponse;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chatrooms")
public class ChatRoomController {
    private final ChatRoomService chatRoomService;
    private final ChatRoomMemberService chatRoomMemberService;

    @PostMapping
    public ChatRoomResponse create(@Valid @RequestBody ChatRoomRequest chatRoomRequest){
        ChatRoom chatRoom = chatRoomService.create(chatRoomRequest.getName(), false, null);
        return ChatRoomResponse.from(chatRoom);
    }

    @GetMapping("/unread")
    public List<UnreadCountResponse> getUnreadCounts(@AuthenticationPrincipal CustomUserDetails user) {
        return chatRoomMemberService.getUnreadCounts(user.getMemberId());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomMemberService.markRead(user.getMemberId(), id);
    }

    @GetMapping
    public List<ChatRoomResponse> getChatRooms(){
        List<ChatRoom> chatRoomList = chatRoomService.getAllChatRooms();
        return chatRoomList.stream()
                .map(ChatRoomResponse::from)
                .toList();
    }
}
