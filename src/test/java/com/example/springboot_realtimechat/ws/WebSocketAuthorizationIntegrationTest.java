package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.websocket.WsErrorResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 규칙 빈과 인터셉터가 조립된 상태에서 실제 프레임이 통과·거부되는지 확인한다.
 * 단위 테스트가 각각 통과해도 인터셉터 순서나 빈 배선이 어긋나면 여기서 깨진다.
 */
@SpringBootTest
@Transactional
class WebSocketAuthorizationIntegrationTest {

    @Autowired
    @Qualifier("clientInboundChannel")
    AbstractSubscribableChannel clientInboundChannel;

    @MockitoSpyBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private Member member;
    private Member outsider;
    private Long roomId;

    @BeforeEach
    void setUp() {
        member = memberService.create("member@test.com", "1234", "멤버");
        outsider = memberService.create("outsider@test.com", "1234", "비멤버");
        ChatRoom room = chatRoomService.create("방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(member.getId(), roomId, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 등록된 인터셉터를 실제 순서대로 통과시킨다. 어느 하나가 프레임을 버리면 null이 나온다. */
    private Message<?> through(Message<?> message) {
        for (ChannelInterceptor interceptor : clientInboundChannel.getInterceptors()) {
            message = interceptor.preSend(message, clientInboundChannel);
            if (message == null) {
                return null;
            }
        }
        return message;
    }

    private Message<?> frame(StompCommand command, String destination, Member user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            CustomUserDetails details = new CustomUserDetails(user.getId(), user.getEmail());
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    details, null, details.getAuthorities());
            accessor.setUser(authentication);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void 멤버는_방을_구독한다() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/" + roomId, member);

        assertThat(through(message)).isNotNull();
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 비멤버의_구독은_거부되고_개인_큐로_사유가_간다() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/" + roomId, outsider);

        assertThat(through(message)).isNull();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(String.valueOf(outsider.getId())), eq("/queue/errors"), payload.capture());

        WsErrorResponse sent = (WsErrorResponse) payload.getValue();
        assertThat(sent.code()).isEqualTo("NOT_JOINED_ROOM");
        assertThat(sent.destination()).isEqualTo("/sub/chatrooms/" + roomId);
    }

    @Test
    void 비멤버의_전송은_거부되고_세션은_유지된다() {
        Message<?> message = frame(StompCommand.SEND, "/pub/chatrooms/" + roomId + "/messages", outsider);

        assertThat(through(message)).isNull();
    }

    @Test
    void 멤버는_방에_전송한다() {
        Message<?> message = frame(StompCommand.SEND, "/pub/chatrooms/" + roomId + "/messages", member);

        assertThat(through(message)).isNotNull();
    }

    @Test
    void 미인증_CONNECT는_세션을_닫는다() {
        assertThatThrownBy(() -> through(frame(StompCommand.CONNECT, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 규칙에_없는_목적지_구독은_거부된다() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/notices", member);

        assertThat(through(message)).isNull();
    }

    @Test
    void 와일드카드_목적지_구독은_멤버여도_거부된다() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/*", member);

        assertThat(through(message)).isNull();
    }
}
