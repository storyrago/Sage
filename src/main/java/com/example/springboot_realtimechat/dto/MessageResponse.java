package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.Message;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class MessageResponse {
    Long messageId;
    String content;
    String imageUrl;
    Long memberId;
    String nickname;
    String profileImageUrl;
    Long chatroomId;
    LocalDateTime createdAt;
    Long replyToId;
    LocalDateTime editedAt;
    boolean deleted;

    public MessageResponse(Long messageId, String content, String imageUrl, Long memberId, String nickname, String profileImageUrl, Long chatroomId, LocalDateTime createdAt, Long replyToId, LocalDateTime editedAt, boolean deleted) {
        this.messageId = messageId;
        this.content = content;
        this.imageUrl = imageUrl;
        this.memberId = memberId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.chatroomId = chatroomId;
        this.createdAt = createdAt;
        this.replyToId = replyToId;
        this.editedAt = editedAt;
        this.deleted = deleted;
    }

    public static MessageResponse from(Message message){
        return new MessageResponse(
                message.getId(),
                message.getContent(),
                message.getImageUrl(),
                message.getMember().getId(),
                message.getMember().getNickname(),
                message.getMember().getProfileImageUrl(),
                message.getChatRoom().getId(),
                message.getCreatedAt(),
                message.getReplyTo() != null ? message.getReplyTo().getId() : null,
                message.getEditedAt(),
                message.isDeleted()
        );
    }
}
