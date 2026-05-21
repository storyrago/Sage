package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {
    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public ChatRoom create(String name){
        ChatRoom chatRoom = new ChatRoom(name);
        return chatRoomRepository.save(chatRoom);
    }

    public ChatRoom getChatRoomById(Long chatRoomId){
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public List<ChatRoom> getAllChatRooms(){
        return chatRoomRepository.findAll();
    }
}
