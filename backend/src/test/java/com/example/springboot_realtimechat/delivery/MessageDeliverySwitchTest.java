package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.controller.ChatMessageController;
import com.example.springboot_realtimechat.domain.message.delivery.MessageBroadcaster;
import com.example.springboot_realtimechat.domain.message.delivery.OutboxMessageBroadcaster;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;
import com.example.springboot_realtimechat.outbox.MessageEventTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 전달을 Kafka로 돌리면 컨트롤러는 Redis로 방송하지 않는다. 전파는 함께 커밋된 outbox 이벤트가 맡는다.
@SpringBootTest(properties = {
        "app.message.delivery=kafka",
        "app.outbox.enabled=true",
        "app.outbox.schema-registry-url=mock://" + MessageDeliverySwitchTest.SCOPE,
        // 브로커 없이 배선만 본다. 소비자 컨테이너를 띄우지 않는다.
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Transactional
class MessageDeliverySwitchTest {

    static final String SCOPE = "delivery-switch-test";

    static {
        MessageEventTestSupport.register(SCOPE);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ChatMessageController chatMessageController;
    @Autowired MessageBroadcaster messageBroadcaster;
    @Autowired SimpleBrokerMessageHandler simpleBrokerMessageHandler;

    @MockitoBean RedisPublisher redisPublisher;

    private Member author;
    private Long roomId;

    @BeforeEach
    void setUp() {
        author = memberService.create("switch-author@e.com", "1234", "스위치");
        ChatRoom room = chatRoomService.create("스위치방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
    }

    @Test
    void 방송기는_outbox_경로다() {
        assertThat(messageBroadcaster).isInstanceOf(OutboxMessageBroadcaster.class);
    }

    @Test
    void REST로_보낸_메시지를_Redis로_방송하지_않는다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(author.getId(), author.getEmail());
        MessageRequest request = new MessageRequest();
        request.setContent("REST");

        mockMvc.perform(post("/api/chatrooms/{chatroomId}/messages", roomId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(redisPublisher, never()).publish(any());
    }

    @Test
    void STOMP로_보낸_메시지를_Redis로_방송하지_않는다() {
        CustomUserDetails userDetails = new CustomUserDetails(author.getId(), author.getEmail());
        Authentication principal = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        MessageRequest request = new MessageRequest();
        request.setContent("STOMP");

        chatMessageController.sendMessage(roomId, request, principal);

        verify(redisPublisher, never()).publish(any());
    }

    @Test
    void 세션마다_보낸_순서대로_내보낸다() {
        // 소비자는 방 순서대로 보낸다. 송신 채널의 스레드 풀이 그 순서를 섞지 않게 한다.
        assertThat(simpleBrokerMessageHandler.isPreservePublishOrder()).isTrue();
    }
}
