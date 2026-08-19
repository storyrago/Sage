package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** 업로드 파일 검증과 키 조립. 공개 접두사에 임의 파일이 올라가지 못하게 막는다. */
public final class ImageUploads {

    private ImageUploads() {}

    public enum Purpose {
        PROFILE("profiles/"),
        CHAT("rooms/");

        private final String prefix;

        Purpose(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    /** 조립된 URL이 이미지 URL 컬럼(500자) 안에 들어가도록 파일명 길이를 여기서 한 번만 묶는다. */
    private static final int MAX_FILENAME = 64;

    /**
     * 쓰기 경계에서 요구하는 키 문법. {@link S3Service#upload}가 실제로 만들 수 있는 형태와 정확히 같다.
     * ".." 경로, 인코딩 우회, 소유자가 없는 평면 키, 컬럼을 넘기는 긴 파일명이 여기서 걸린다.
     */
    public static final Pattern KEY_SYNTAX =
            Pattern.compile("^(rooms|profiles)/\\d+/[0-9a-f-]{36}_[A-Za-z0-9.-]{1," + MAX_FILENAME + "}$");

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif");

    /** 기본값을 두지 않는다. 용도를 잘못 지정한 업로드가 조용히 공개되면 안 된다. */
    public static Purpose parsePurpose(String raw) {
        if ("profile".equals(raw)) return Purpose.PROFILE;
        if ("chat".equals(raw)) return Purpose.CHAT;
        throw new CustomException(ErrorCode.INVALID_IMAGE_PURPOSE);
    }

    // 컨테이너 힙(~250MB) 기준으로 라스터 상한을 잡는다. ImageIO 디코딩은 픽셀당 4바이트라 16MP가 약 64MB.
    private static final long MAX_PIXELS = 16_000_000L;

    /** 순수 함수로 분리해 거대 이미지를 실제로 디코딩하지 않고도 한계값을 테스트할 수 있게 한다. */
    public static void rejectIfTooLarge(int width, int height) {
        if ((long) width * (long) height > MAX_PIXELS) {
            throw new CustomException(ErrorCode.IMAGE_TOO_LARGE);
        }
    }

    /**
     * 실제로 디코딩되는 이미지인지 확인하고, 검증된 content-type을 돌려준다.
     * content-type은 클라이언트가 보내는 값이라 그것만 믿으면 임의 파일이 올라간다.
     */
    public static String verifyImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_IMAGE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new CustomException(ErrorCode.INVALID_IMAGE);
        }
        try (InputStream in = file.getInputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                throw new CustomException(ErrorCode.INVALID_IMAGE);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new CustomException(ErrorCode.INVALID_IMAGE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                rejectIfTooLarge(reader.getWidth(0), reader.getHeight(0));
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new CustomException(ErrorCode.INVALID_IMAGE);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_IMAGE);
        }
        return contentType;
    }

    /** 키가 그대로 URL 경로가 되므로 영숫자·점·하이픈만 남긴다. */
    public static String sanitizeFilename(String original) {
        if (original == null) return "image";
        // 슬래시 제거가 경로 구분자를 제거하므로 정규화된 이름은 키 접두사를 벗어날 수 없다. 남은 점은 무해하다.
        String cleaned = original.replaceAll("[^A-Za-z0-9.-]", "");
        if (cleaned.isBlank()) return "image";
        return cleaned.length() > MAX_FILENAME ? cleaned.substring(0, MAX_FILENAME) : cleaned;
    }
}
