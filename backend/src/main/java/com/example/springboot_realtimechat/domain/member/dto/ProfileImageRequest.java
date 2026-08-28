package com.example.springboot_realtimechat.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfileImageRequest {
    @NotBlank
    // 컬럼 길이와 같게 둔다. 지금은 키 문법 검증이 먼저 걸러내 도달하지 않지만,
    // 그 검증이 느슨해지면 여기가 마지막으로 막는다.
    @Size(max = 500)
    private String imageUrl;
}
