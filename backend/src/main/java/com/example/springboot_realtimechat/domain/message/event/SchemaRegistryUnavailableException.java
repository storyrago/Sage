package com.example.springboot_realtimechat.domain.message.event;

/** 레지스트리에 닿지 못해 이벤트를 읽지 못함. 레지스트리가 돌아오면 같은 바이트를 다시 읽어 성공할 수 있다. */
public class SchemaRegistryUnavailableException extends RuntimeException {

    public SchemaRegistryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
