package com.example.springboot_realtimechat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 원시 boolean을 쓰지 않는다. 필드가 빠진 요청이 조용히 false(잠금 해제)가 되면 안 된다.
 */
@Getter
@Setter
public class RoomPrivacyRequest {
    @JsonProperty("private")
    private Boolean isPrivate;
}
