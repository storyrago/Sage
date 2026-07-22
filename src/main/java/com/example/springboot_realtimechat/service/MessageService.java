package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {
    private final MessageRepository messageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberService memberService;
    private final ChatRoomService chatRoomService;

    @Transactional
    public Message create(String content, String imageUrl, Long memberId, Long chatroomId, Long replyToId){
        if ((content == null || content.isBlank()) && (imageUrl == null || imageUrl.isBlank())) {
            throw new CustomException(ErrorCode.EMPTY_MESSAGE);
        }

        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);

        boolean exists = chatRoomMemberRepository.existsByMemberAndChatRoom(member, chatRoom);
        if(!exists){
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }

        Message replyTo = replyToId != null
                ? messageRepository.findById(replyToId).orElse(null)
                : null;
        if (replyTo != null && !replyTo.getChatRoom().getId().equals(chatRoom.getId())) {
            replyTo = null; // 다른 방 메시지엔 답장 링크하지 않음
        }

        Message message = new Message(content, imageUrl, member, chatRoom, replyTo);
        return messageRepository.save(message);
    }

    public Message getMessageById(Long messageId){
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new CustomException(ErrorCode.MESSAGE_NOT_FOUND));
    }

    public List<Message> getAllChatRoomMessages(Long chatroomId){
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);
        return messageRepository.findByChatRoomOrderById(chatRoom);
    }
}
