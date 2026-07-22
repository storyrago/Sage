package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.dto.LoginRequest;
import com.example.springboot_realtimechat.dto.LoginResponse;
import com.example.springboot_realtimechat.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request){
        return authService.login(loginRequest, clientIp(request));
    }

    // nginx 뒤이므로 X-Forwarded-For의 첫 IP가 실제 클라이언트. 없으면 remoteAddr.
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
