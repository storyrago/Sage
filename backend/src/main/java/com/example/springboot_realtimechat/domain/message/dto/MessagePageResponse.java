package com.example.springboot_realtimechat.domain.message.dto;

import lombok.Getter;
import java.util.List;

@Getter
public class MessagePageResponse {
    private final List<MessageResponse> messages;
    private final boolean hasMore;

    public MessagePageResponse(List<MessageResponse> messages, boolean hasMore) {
        this.messages = messages;
        this.hasMore = hasMore;
    }
}
