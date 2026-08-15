package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageRequest {
    String content;
    String imageUrl;
    Long replyToId;
}
