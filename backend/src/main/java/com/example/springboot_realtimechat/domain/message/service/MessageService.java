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
import com.example.springboot_realtimechat.domain.message.event.MessageEventRecorder;
import com.example.springboot_realtimechat.domain.message.event.MessageEventType;
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
import java.util.Optional;

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
    private final MessageEventRecorder messageEventRecorder;

    public record MessagePage(List<Message> messages, boolean hasMore) {}

    @Transactional
    public Message create(String content, String imageUrl, Long memberId, Long chatroomId, Long replyToId) {
        return create(content, imageUrl, memberId, chatroomId, replyToId, null);
    }

    /**
     * clientMessageId가 있으면 재전송을 식별한다. 같은 회원이 같은 값으로 다시 보내면
     * 새로 저장하지 않고 처음 저장된 메시지를 돌려준다(순번도 다시 쓰지 않는다).
     */
    @Transactional
    public Message create(String content, String imageUrl, Long memberId, Long chatroomId, Long replyToId,
                          String clientMessageId) {
        if ((content == null || content.isBlank()) && (imageUrl == null || imageUrl.isBlank())) {
            throw new CustomException(ErrorCode.EMPTY_MESSAGE);
        }

        Member member = memberService.getMemberById(memberId);
        // 방 행을 잠그고 시작한다. 순번 발급부터 커밋까지 이 잠금 아래에서 일어나야
        // 같은 방 안에서 순번 순서 = 커밋 순서가 된다. 잠금 순서는 위임·나가기·강퇴와 같은 chatrooms → chatroom_members다.
        ChatRoom chatRoom = chatRoomService.getChatRoomByIdForUpdate(chatroomId);

        if (!roomAccess.isMember(memberId, chatroomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }

        // 재전송 확인은 방 잠금 아래에서 한다. 같은 방으로 동시에 들어온 재전송은 잠금에서 줄을 서므로
        // 뒤쪽이 앞쪽의 커밋을 반드시 본다.
        if (clientMessageId != null) {
            Optional<Message> existing =
                    messageRepository.findByMemberIdAndClientMessageIdForUpdate(memberId, clientMessageId);
            if (existing.isPresent()) {
                if (!existing.get().getChatRoom().getId().equals(chatroomId)) {
                    throw new CustomException(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT);
                }
                return existing.get();
            }
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
        Message message = new Message(content == null ? "" : content, imageUrl, member, chatRoom, replyTo,
                chatRoom.nextMessageSeq(), clientMessageId);
        Message saved = messageRepository.save(message);
        // 같은 트랜잭션에서 이벤트를 남긴다. 방 잠금 아래이므로 방 안 이벤트 순서 = 순번 순서다.
        // 재전송(위의 기존 메시지 반환)은 여기까지 오지 않으므로 이벤트가 중복되지 않는다.
        messageEventRecorder.record(MessageEventType.CREATED, saved);
        return saved;
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
        messageEventRecorder.record(MessageEventType.UPDATED, message);
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
        messageEventRecorder.record(MessageEventType.DELETED, message);

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
