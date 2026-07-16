package com.example.springboot_realtimechat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;                 // S3Config의 빈 주입

    @Value("${aws.s3.bucket}")
    private String bucket;
    @Value("${aws.s3.region}")
    private String region;

    public String upload(MultipartFile file) {
        // ① 고유한 파일 이름(key) 생성
        String key = UUID.randomUUID() + "_" + file.getOriginalFilename();

        // ② 업로드 요청 만들기
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .build();

        // ③ 실제 업로드
        try {
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException("S3 업로드 실패", e);
        }

        // ④ 공개 URL 조립해서 반환
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }
}