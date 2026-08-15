package com.example.springboot_realtimechat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

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

    public String upload(MultipartFile file, ImageUploads.Purpose purpose) {
        String contentType = ImageUploads.verifyImage(file);
        String key = purpose.prefix() + UUID.randomUUID() + "_" + ImageUploads.sanitizeFilename(file.getOriginalFilename());

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)   // 클라이언트가 보낸 값이 아니라 검증된 값을 쓴다
                .build();

        try {
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException("S3 업로드 실패", e);
        }

        return publicUrlPrefix() + key;
    }

    // 참조가 끊긴 객체에 orphan 태그를 단다. 삭제는 버킷 수명주기 규칙이 한다.
    public void tagAsOrphan(String url) {
        String key = extractKey(url);
        if (key == null) return;          // 우리 버킷 객체가 아니면 아무것도 하지 않는다

        s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(bucket)
                .key(key)
                .tagging(Tagging.builder()
                        .tagSet(Tag.builder().key("orphan").value("true").build())
                        .build())
                .build());
    }

    private String publicUrlPrefix() {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/";
    }

    // 우리 버킷 URL이면 키를, 아니면 null을 돌려준다.
    // 업로드가 URL을 문자열 연결로 조립하므로 역변환도 접두사 제거로 정확히 일치한다.
    private String extractKey(String url) {
        if (url == null || url.isBlank()) return null;

        String prefix = publicUrlPrefix();
        if (!url.startsWith(prefix)) return null;

        String key = url.substring(prefix.length());
        return key.isBlank() ? null : key;
    }
}