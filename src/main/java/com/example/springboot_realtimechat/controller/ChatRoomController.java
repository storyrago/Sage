package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.dto.ChatRoomRequest;
import com.example.springboot_realtimechat.dto.ChatRoomResponse;
import com.example.springboot_realtimechat.service.ChatRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chatrooms")
public class ChatRoomController {
    private final ChatRoomService chatRoomService;

    @PostMapping
    public ChatRoomResponse create(@Valid @RequestBody ChatRoomRequest chatRoomRequest){
        ChatRoom chatRoom = chatRoomService.create(chatRoomRequest.getName());
        return ChatRoomResponse.from(chatRoom);
    }

    @GetMapping("/{id}")
    public ChatRoomResponse getChatRoom(@PathVariable Long id){
        ChatRoom chatRoom = chatRoomService.getChatRoomById(id);
        return ChatRoomResponse.from(chatRoom);
    }

    @GetMapping
    public List<ChatRoomResponse> getChatRooms(){
        List<ChatRoom> chatRoomList = chatRoomService.getAllChatRooms();
        return chatRoomList.stream()
                .map(ChatRoomResponse::from)
                .toList();
    }
}
