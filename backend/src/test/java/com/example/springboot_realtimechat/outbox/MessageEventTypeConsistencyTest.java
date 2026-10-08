package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventType;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MessageEventTypeConsistencyTest {

    @Test
    void 도메인_타입과_생성된_Avro_타입의_상수_이름이_같다() {
        Set<String> domainNames = Arrays.stream(MessageEventType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        Set<String> avroNames = Arrays.stream(com.example.springboot_realtimechat.events.MessageEventType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(domainNames).isEqualTo(avroNames);
    }
}
