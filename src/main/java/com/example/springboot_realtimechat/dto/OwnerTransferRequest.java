package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

/** 요청자는 언제나 JWT에서 온다. 여기 담기는 것은 넘겨받을 사람뿐이다. */
@Getter
@Setter
public class OwnerTransferRequest {
    private Long memberId;
}
