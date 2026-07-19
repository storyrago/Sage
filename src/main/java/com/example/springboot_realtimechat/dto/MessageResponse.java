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
    Long chatroomId;
    LocalDateTime createdAt;

    public MessageResponse(Long messageId, String content, String imageUrl, Long memberId, String nickname, Long chatroomId, LocalDateTime createdAt) {
        this.messageId = messageId;
        this.content = content;
        this.imageUrl = imageUrl;
        this.memberId = memberId;
        this.nickname = nickname;
        this.chatroomId = chatroomId;
        this.createdAt = createdAt;
    }

    public static MessageResponse from(Message message){
        return new MessageResponse(
                message.getId(),
                message.getContent(),
                message.getImageUrl(),
                message.getMember().getId(),
                message.getMember().getNickname(),
                message.getChatRoom().getId(),
                message.getCreatedAt()
        );
    }
}
