package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
    @Email
    @NotBlank
    String email;

    @NotBlank
    String password;
}
