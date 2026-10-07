package com.example.springboot_realtimechat.global.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled           메시지 이벤트를 outbox에 남길지. 기본 false — 켜려면 레지스트리에 스키마가 등록돼 있어야 한다
 * @param schemaRegistryUrl 스키마 레지스트리 주소. 테스트는 mock://<scope>를 쓴다
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(boolean enabled, String schemaRegistryUrl) {
}
