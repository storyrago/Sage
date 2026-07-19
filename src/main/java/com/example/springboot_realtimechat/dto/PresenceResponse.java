package com.example.springboot_realtimechat.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class PresenceResponse {
    private final List<Long> onlineMemberIds;

    public PresenceResponse(List<Long> onlineMemberIds) {
        this.onlineMemberIds = onlineMemberIds;
    }
}
