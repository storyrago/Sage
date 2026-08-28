package com.example.springboot_realtimechat.domain.message.service;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.chatroom.service.RoomAccess;
import com.example.springboot_realtimechat.domain.image.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.domain.image.service.ImageUploads;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

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
    private final MemberService memberService;
    private final ChatRoomService chatRoomService;
    private final ApplicationEventPublisher eventPublisher;
    private final RoomAccess roomAccess;
    private final S3Service s3Service;

    public record MessagePage(List<Message> messages, boolean hasMore) {}

    @Transactional
    public Message create(String content, String imageUrl, Long memberId, Long chatroomId, Long replyToId){
        if ((content == null || content.isBlank()) && (imageUrl == null || imageUrl.isBlank())) {
            throw new CustomException(ErrorCode.EMPTY_MESSAGE);
        }

        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);

        if (!roomAccess.isMember(memberId, chatroomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }

        // 메시지 이미지는 보내는 사람이 이 방 용도로 올린 키만 참조할 수 있다.
        if (imageUrl != null && !imageUrl.isBlank()) {
            s3Service.requireOwnKey(imageUrl, ImageUploads.Purpose.CHAT.prefix() + memberId + "/");
        }

        Message replyTo = replyToId != null
                ? messageRepository.findById(replyToId).orElse(null)
                : null;
        if (replyTo != null && !replyTo.getChatRoom().getId().equals(chatRoom.getId())) {
            replyTo = null; // 다른 방 메시지엔 답장 링크하지 않음
        }

        // content 컬럼은 NOT NULL이므로, 이미지 전용 메시지(content=null)를 저장하려면 빈 문자열로 정규화한다
        Message message = new Message(content == null ? "" : content, imageUrl, member, chatRoom, replyTo);
        return messageRepository.save(message);
    }

    public Message getMessageById(Long messageId){
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new CustomException(ErrorCode.MESSAGE_NOT_FOUND));
    }

    public MessagePage getMessages(Long chatroomId, Long memberId, Long before, int limit) {
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatroomId);
        if (!roomAccess.isMember(memberId, chatroomId)) {
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
    public Message update(Long chatroomId, Long messageId, Long memberId, String content) {
        requireMember(memberId, chatroomId);
        Message message = getMessageById(messageId); // MESSAGE_NOT_FOUND on miss
        requireSameRoom(message, chatroomId);
        // 작성자가 없는 메시지(탈퇴자)는 아무도 수정·삭제할 수 없다.
        if (message.getMember() == null || !message.getMember().getId().equals(memberId)) {
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
    public Message delete(Long chatroomId, Long messageId, Long memberId) {
        requireMember(memberId, chatroomId);
        Message message = getMessageById(messageId);
        requireSameRoom(message, chatroomId);
        // 작성자가 없는 메시지(탈퇴자)는 아무도 수정·삭제할 수 없다.
        if (message.getMember() == null || !message.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        String imageUrl = message.getImageUrl();        // softDelete가 참조를 지우기 전에 읽는다
        message.softDelete();

        if (imageUrl != null && !imageUrl.isBlank()) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(imageUrl));
        }
        return message;
    }

    /** 전파 목적지는 엔티티의 방이므로 인가도 엔티티의 방을 기준으로 한다. */
    private void requireSameRoom(Message message, Long chatroomId) {
        if (!message.getChatRoom().getId().equals(chatroomId)) {
            throw new CustomException(ErrorCode.MESSAGE_NOT_FOUND);
        }
    }

    private void requireMember(Long memberId, Long chatroomId) {
        if (!roomAccess.isMember(memberId, chatroomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
    }
}
