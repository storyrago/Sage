package com.example.springboot_realtimechat.domain.image.controller;

import com.example.springboot_realtimechat.domain.image.service.ImageUploads;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/images")
public class ImageController {

    private final S3Service s3Service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", required = false) String purpose,
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        String url = s3Service.upload(file, ImageUploads.parsePurpose(purpose), customUserDetails.getMemberId());
        return ResponseEntity.ok(Map.of("url", url));
    }
}