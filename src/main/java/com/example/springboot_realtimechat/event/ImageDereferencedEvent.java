package com.example.springboot_realtimechat.event;

// 참조가 끊긴 이미지 URL. 커밋 이후 정리 대상이 된다.
public record ImageDereferencedEvent(String url) {
}
