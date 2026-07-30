package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {
    private final MessageRepository messageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberService memberService;
    private final ChatRoomService chatRoomService;
    private final ApplicationEventPublisher eventPublisher;

    public record MessagePage(List<Message> messages, boolean hasMore) {}

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

    public MessagePage getMessages(Long chatroomId, Long memberId, Long before, int limit) {
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);
        if (!chatRoomMemberRepository.existsByMemberAndChatRoom(member, chatRoom)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
        Pageable pageable = PageRequest.of(0, limit + 1);
        List<Message> desc = (before == null)
                ? messageRepository.findLatestByChatRoom(chatRoom, pageable)
                : messageRepository.findOlderByChatRoom(chatRoom, before, pageable);
        boolean hasMore = desc.size() > limit;
        List<Message> page = hasMore ? new ArrayList<>(desc.subList(0, limit)) : new ArrayList<>(desc);
        Collections.reverse(page); // 오름차순(오래된 → 최신)
        return new MessagePage(page, hasMore);
    }

    @Transactional
    public Message update(Long messageId, Long memberId, String content) {
        Message message = getMessageById(messageId); // MESSAGE_NOT_FOUND on miss
        if (!message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        if (message.isDeleted()) {
            throw new CustomException(ErrorCode.MESSAGE_NOT_FOUND); // 삭제된 메시지는 수정 불가
        }
        if (content == null || content.isBlank()) {
            throw new CustomException(ErrorCode.EMPTY_MESSAGE);
        }
        message.edit(content); // 더티체킹으로 반영
        return message;
    }

    @Transactional
    public Message delete(Long messageId, Long memberId) {
        Message message = getMessageById(messageId);
        if (!message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        String imageUrl = message.getImageUrl();        // softDelete가 참조를 지우기 전에 읽는다
        message.softDelete();

        if (imageUrl != null && !imageUrl.isBlank()) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(imageUrl));
        }
        return message;
    }
}
