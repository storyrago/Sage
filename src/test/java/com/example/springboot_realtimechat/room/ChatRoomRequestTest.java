package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.dto.ChatRoomRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** @JsonProperty("private") 역직렬화 계약을 실제 JSON 파싱 결과로 고정한다. */
class ChatRoomRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void private_필드가_true면_isPrivate로_역직렬화된다() throws Exception {
        ChatRoomRequest request = objectMapper.readValue(
                "{\"name\":\"x\",\"private\":true}", ChatRoomRequest.class);

        assertThat(request.isPrivate()).isTrue();
    }

    @Test
    void private_필드가_없으면_isPrivate가_false다() throws Exception {
        ChatRoomRequest request = objectMapper.readValue(
                "{\"name\":\"x\"}", ChatRoomRequest.class);

        assertThat(request.isPrivate()).isFalse();
    }
}
