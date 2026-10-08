package com.example.springboot_realtimechat.domain.image.event;

/**
 * 메시지 삭제로 참조가 끊긴 이미지의 정리를 맡긴다. 삭제 트랜잭션 안에서 부른다.
 * 경로는 app.message.delivery로 고른다(redis: 커밋 후 리스너, kafka: 삭제 이벤트를 읽는 이미지 정리 소비자).
 */
public interface MessageImageRelease {

    void release(String imageUrl);
}
