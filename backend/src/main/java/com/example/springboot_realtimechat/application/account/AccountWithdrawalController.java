package com.example.springboot_realtimechat.application.account;

import com.example.springboot_realtimechat.global.auth.CustomUserDetails;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members")
public class AccountWithdrawalController {
    private final AccountWithdrawalService accountWithdrawalService;

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal CustomUserDetails customUserDetails){
        accountWithdrawalService.withdraw(customUserDetails.getMemberId());
        return ResponseEntity.noContent().build();
    }
}
