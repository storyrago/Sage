package com.example.springboot_realtimechat.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class PresenceResponse {
    private final Long roomId;
    private final List<Long> onlineMemberIds;

    public PresenceResponse(Long roomId, List<Long> onlineMemberIds) {
        this.roomId = roomId;
        this.onlineMemberIds = onlineMemberIds;
    }
}
