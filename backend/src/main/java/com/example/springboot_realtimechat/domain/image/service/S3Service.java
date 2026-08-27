package com.example.springboot_realtimechat.domain.image.service;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;                 // S3Config의 빈 주입
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;
    @Value("${aws.s3.region}")
    private String region;

    public String upload(MultipartFile file, ImageUploads.Purpose purpose, Long uploaderId) {
        String contentType = ImageUploads.verifyImage(file);
        String name = UUID.randomUUID() + "_" + ImageUploads.sanitizeFilename(file.getOriginalFilename());
        // 프로필도 업로더를 키에 담는다. 소유자가 키에 있어야 쓰기 경계에서 남의 이미지 지정을 막을 수 있다.
        String key = purpose.prefix() + uploaderId + "/" + name;

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

    /**
     * DB에 이미지 URL을 넣기 전에 소유와 형식을 확인한다.
     * 기대 접두사는 호출자가 목적에 맞게 명시한다 — 전역 판정 하나가 무엇을 써도 되는지 혼자 정하지 않는다.
     * 읽기 경로와 달리 판정 실패는 통과가 아니라 거부다.
     */
    public void requireOwnKey(String url, String expectedPrefix) {
        String key = extractKey(url);
        if (key == null
                || !key.startsWith(expectedPrefix)
                || !ImageUploads.KEY_SYNTAX.matcher(key).matches()) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_REFERENCE);
        }
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

    /**
     * 채팅 이미지는 서명된 URL로만 읽는다.
     * profiles/는 공개이므로 서명하지 않는다 — 서명하면 응답마다 URL이 바뀌어 아바타 캐시가 무효화된다.
     * 우리 버킷이 아닌 값은 그대로 돌려준다(본문에 박힌 외부 URL 하위호환).
     */
    public String presignedGetUrl(String storedUrl, Duration ttl) {
        String key = extractKey(storedUrl);
        if (key == null) return storedUrl;
        if (key.startsWith(ImageUploads.Purpose.PROFILE.prefix())) return storedUrl;

        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }

    private String publicUrlPrefix() {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/";
    }

    // 우리 버킷 URL이면 키를, 아니면 null을 돌려준다.
    // 업로드가 URL을 문자열 연결로 조립하므로 역변환도 접두사 제거로 정확히 일치한다.
    public String extractKey(String url) {
        if (url == null || url.isBlank()) return null;

        String prefix = publicUrlPrefix();
        if (!url.startsWith(prefix)) return null;

        String key = url.substring(prefix.length());
        return key.isBlank() ? null : key;
    }
}