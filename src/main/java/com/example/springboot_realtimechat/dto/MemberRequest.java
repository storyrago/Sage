package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemberRequest {
    @Email
    @NotBlank
    String email;

    @NotBlank
    String password;

    @NotBlank
    @Size(max = 10)
    String nickname;
}
