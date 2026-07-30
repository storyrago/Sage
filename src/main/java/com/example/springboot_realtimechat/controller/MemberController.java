package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.MemberResponse;
import com.example.springboot_realtimechat.dto.NicknameRequest;
import com.example.springboot_realtimechat.dto.ProfileImageRequest;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members")
public class MemberController {
    private final MemberService memberService;

    @GetMapping
    public List<MemberResponse> getAllMembers(){
        List<Member> memberList = memberService.getMemberList();
        return memberList.stream()
                .map(MemberResponse::from)
                .toList();
    }

    @GetMapping("/me")
    public MemberResponse getMe(@AuthenticationPrincipal CustomUserDetails customUserDetails){
        Member member = memberService.getMemberById(customUserDetails.getMemberId());
        return MemberResponse.from(member);
    }

    @GetMapping("/{id}")
    public MemberResponse getMemberById(@PathVariable Long id){
        Member member = memberService.getMemberById(id);
        return MemberResponse.from(member);
    }

    @PatchMapping("/me")
    public MemberResponse updateMe(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody NicknameRequest request) {

        Member member = memberService.updateNickname(
                customUserDetails.getMemberId(),
                request.getNickname());

        return MemberResponse.from(member);
    }

    @PostMapping("/me/onboarding")
    public MemberResponse completeOnboarding(
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {

        Member member = memberService.completeOnboarding(customUserDetails.getMemberId());
        return MemberResponse.from(member);
    }

    @PatchMapping("/me/profile-image")
    public MemberResponse updateProfileImage(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody ProfileImageRequest request) {

        Member member = memberService.updateProfileImage(
                customUserDetails.getMemberId(),
                request.getImageUrl());

        return MemberResponse.from(member);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal CustomUserDetails customUserDetails){
        memberService.delete(customUserDetails.getMemberId());
        return ResponseEntity.noContent().build();
    }
}