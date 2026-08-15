package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.controller.ChatMessageController;
import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.MessageRequest;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.redis.RedisPublisher;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.JwtTokenProvider;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 메시지 생성 경로는 REST(MessageController)와 STOMP(ChatMessageController) 둘뿐이다.
// 둘 다 저장 후 redisPublisher.publish를 호출해야 방 전체에 실시간 전파된다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessageBroadcastTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatMessageController chatMessageController;

    @MockitoBean RedisPublisher redisPublisher;
    @MockitoBean S3Service s3Service;

    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    private String RAW_IMAGE_URL;
    private Member author;
    private Long roomId;

    @BeforeEach
    void setUp() {
        author = memberService.create("broadcast-author@e.com", "1234", "발신자");
        ChatRoom room = chatRoomService.create("방송방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
        RAW_IMAGE_URL = BUCKET_PREFIX + "rooms/" + author.getId() + "/stomp_photo.png";

        // 입력을 그대로 감싼 값을 돌려줘서, 발행된 imageUrl이 서명을 거쳤는지 그대로 드러나게 한다.
        when(s3Service.presignedGetUrl(any(), any()))
                .thenAnswer(invocation -> "SIGNED::" + invocation.getArgument(0));
    }

    @Test
    void REST로_보낸_메시지도_방에_발행된다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(author.getId(), author.getEmail());
        MessageRequest request = new MessageRequest();
        request.setContent("REST로 보낸 메시지");

        MvcResult result = mockMvc.perform(post("/api/chatrooms/{chatroomId}/messages", roomId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());

        ArgumentCaptor<MessageResponse> captor = ArgumentCaptor.forClass(MessageResponse.class);
        verify(redisPublisher).publish(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(responseJson.get("messageId").asLong());
        assertThat(captor.getValue().getChatroomId()).isEqualTo(responseJson.get("chatroomId").asLong());
        assertThat(captor.getValue().getContent()).isEqualTo("REST로 보낸 메시지");
    }

    @Test
    void STOMP로_보낸_메시지도_방에_발행된다() {
        CustomUserDetails userDetails = new CustomUserDetails(author.getId(), author.getEmail());
        Authentication principal = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        MessageRequest request = new MessageRequest();
        request.setContent("STOMP로 보낸 메시지");

        chatMessageController.sendMessage(roomId, request, principal);

        ArgumentCaptor<MessageResponse> captor = ArgumentCaptor.forClass(MessageResponse.class);
        verify(redisPublisher).publish(captor.capture());
        assertThat(captor.getValue().getChatroomId()).isEqualTo(roomId);
        assertThat(captor.getValue().getContent()).isEqualTo("STOMP로 보낸 메시지");
    }

    @Test
    void STOMP로_보낸_메시지의_imageUrl도_서명된다() {
        CustomUserDetails userDetails = new CustomUserDetails(author.getId(), author.getEmail());
        Authentication principal = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        MessageRequest request = new MessageRequest();
        request.setContent("사진 보냄");
        request.setImageUrl(RAW_IMAGE_URL);

        chatMessageController.sendMessage(roomId, request, principal);

        ArgumentCaptor<MessageResponse> captor = ArgumentCaptor.forClass(MessageResponse.class);
        verify(redisPublisher).publish(captor.capture());
        assertThat(captor.getValue().getImageUrl()).isEqualTo("SIGNED::" + RAW_IMAGE_URL);
    }
}
