package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.dto.MessageUpdateRequest;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MessageResponseFactory.of(Message)가 이미지 URL을 서명하는 유일한 경로다.
// REST 네 경로(POST/GET/PATCH/DELETE) 중 하나라도 MessageResponse.from(message)로
// 되돌아가면 예외 없이 서명되지 않은 URL이 그대로 응답에 실린다 — 그 상태를 여기서 잡는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessageImageUrlSigningTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageService messageService;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockitoBean S3Service s3Service;
    @MockitoBean RedisPublisher redisPublisher;

    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    private String RAW_IMAGE_URL;
    private Member author;
    private Long roomId;
    private String token;

    @BeforeEach
    void setUp() {
        author = memberService.create("signing-author@e.com", "1234", "작성자");
        ChatRoom room = chatRoomService.create("서명방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
        token = jwtTokenProvider.createAccessToken(author.getId(), author.getEmail());
        RAW_IMAGE_URL = BUCKET_PREFIX + "rooms/" + author.getId() + "/abc_photo.png";

        // 입력을 그대로 감싼 값을 돌려줘서, 응답에 실린 imageUrl이 서명을 거쳤는지 그대로 드러나게 한다.
        when(s3Service.presignedGetUrl(any(), any()))
                .thenAnswer(invocation -> "SIGNED::" + invocation.getArgument(0));
    }

    @Test
    void POST로_보낸_메시지의_imageUrl은_서명된다() throws Exception {
        MessageRequest request = new MessageRequest();
        request.setContent("사진 보냄");
        request.setImageUrl(RAW_IMAGE_URL);

        JsonNode response = objectMapper.readTree(
                mockMvc.perform(post("/api/chatrooms/{chatroomId}/messages", roomId)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(response.get("imageUrl").asText()).isEqualTo("SIGNED::" + RAW_IMAGE_URL);
    }

    @Test
    void GET_목록의_imageUrl도_서명된다() throws Exception {
        messageService.create("사진 메시지", RAW_IMAGE_URL, author.getId(), roomId, null);

        JsonNode response = objectMapper.readTree(
                mockMvc.perform(get("/api/chatrooms/{chatroomId}/messages", roomId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(response.get("messages").get(0).get("imageUrl").asText())
                .isEqualTo("SIGNED::" + RAW_IMAGE_URL);
    }

    @Test
    void PATCH로_수정한_메시지의_imageUrl도_서명된다() throws Exception {
        Message message = messageService.create("원본", RAW_IMAGE_URL, author.getId(), roomId, null);
        MessageUpdateRequest request = new MessageUpdateRequest();
        request.setContent("수정된 내용");

        JsonNode response = objectMapper.readTree(
                mockMvc.perform(patch("/api/chatrooms/{chatroomId}/messages/{messageId}", roomId, message.getId())
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(response.get("imageUrl").asText())
                .isEqualTo("SIGNED::" + RAW_IMAGE_URL);
    }

    // 삭제는 도메인 규칙(Message.softDelete)이 imageUrl 자체를 null로 지운다.
    // 그래도 factory는 그 null을 다시 s3Service.presignedGetUrl에 넘겨 서명을 거친다 —
    // MessageResponse.from(message)로 되돌아가면 이 호출 자체가 사라지므로 여전히 회귀를 잡는다.
    @Test
    void DELETE_응답의_imageUrl도_서명을_거친다() throws Exception {
        Message message = messageService.create("삭제될 메시지", RAW_IMAGE_URL, author.getId(), roomId, null);

        JsonNode response = objectMapper.readTree(
                mockMvc.perform(delete("/api/chatrooms/{chatroomId}/messages/{messageId}", roomId, message.getId())
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(response.get("imageUrl").asText()).isEqualTo("SIGNED::null");
    }

}
