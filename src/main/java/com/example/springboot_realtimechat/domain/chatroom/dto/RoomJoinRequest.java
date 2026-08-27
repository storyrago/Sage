package com.example.springboot_realtimechat.domain.chatroom.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 입장 요청 본문. 요청자는 언제나 JWT에서 오므로 신원 필드는 두지 않는다.
 */
@Getter
@Setter
public class RoomJoinRequest {
    private String inviteCode;
}
