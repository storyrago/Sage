package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.delivery.MessageEventFixtures;
import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.events.MessageEventType;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageResponseFactoryTest {

    private Message messageWithImage(String imageUrl) {
        Member author = mock(Member.class);
        when(author.getId()).thenReturn(1L);
        when(author.getNickname()).thenReturn("작성자");
        when(author.getProfileImageUrl()).thenReturn("https://bucket/profiles/me.png");

        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(7L);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(11L);
        when(message.getContent()).thenReturn("본문");
        when(message.getImageUrl()).thenReturn(imageUrl);
        when(message.getMember()).thenReturn(author);
        when(message.getChatRoom()).thenReturn(room);
        return message;
    }

    @Test
    void 이미지_URL을_서명된_URL로_바꾼다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(eq("https://bucket/rooms/a.png"), any(Duration.class)))
                .thenReturn("https://signed/a.png?sig=1");

        MessageResponse response = new MessageResponseFactory(s3Service)
                .of(messageWithImage("https://bucket/rooms/a.png"));

        assertThat(response.getImageUrl()).isEqualTo("https://signed/a.png?sig=1");
    }

    @Test
    void 서명_만료는_1시간이다() {
        S3Service s3Service = mock(S3Service.class);
        new MessageResponseFactory(s3Service).of(messageWithImage("https://bucket/rooms/a.png"));

        verify(s3Service).presignedGetUrl(eq("https://bucket/rooms/a.png"), eq(Duration.ofHours(1)));
    }

    // 프로필 사진은 서명 대상이 아니다. 서명 여부 판정은 S3Service가 하므로 팩토리는 건드리지 않는다.
    @Test
    void 작성자_프로필_사진은_그대로_둔다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(any(), any(Duration.class))).thenReturn("서명됨");

        MessageResponse response = new MessageResponseFactory(s3Service)
                .of(messageWithImage("https://bucket/rooms/a.png"));

        assertThat(response.getProfileImageUrl()).isEqualTo("https://bucket/profiles/me.png");
    }

    @Test
    void 이미지가_없는_메시지도_처리한다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(isNull(), any(Duration.class))).thenReturn(null);

        MessageResponse response = new MessageResponseFactory(s3Service).of(messageWithImage(null));

        assertThat(response.getImageUrl()).isNull();
        assertThat(response.getMessageId()).isEqualTo(11L);
    }

    @Test
    void 이벤트로_만든_응답은_저장된_메시지와_같은_모양이다() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(any(), any(Duration.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var event = MessageEventFixtures.event(MessageEventType.UPDATED, 7L, 11L, 3L, 1L, 10L, false);

        MessageResponse response = new MessageResponseFactory(s3Service).of(event);

        assertThat(response.getMessageId()).isEqualTo(11L);
        assertThat(response.getChatroomId()).isEqualTo(7L);
        assertThat(response.getSeq()).isEqualTo(3L);
        assertThat(response.getMemberId()).isEqualTo(1L);
        assertThat(response.getNickname()).isEqualTo("작성자");
        assertThat(response.getContent()).isEqualTo("본문");
        assertThat(response.getReplyToId()).isEqualTo(10L);
        assertThat(response.getCreatedAt()).isEqualTo(event.getCreatedAt());
        assertThat(response.isDeleted()).isFalse();
    }

    @Test
    void 이벤트의_이미지_URL도_전달_시점에_서명한다() {
        // 이벤트에는 서명 전 값이 실린다. 서명 URL은 1시간 뒤 만료되므로 보내는 순간 서명한다.
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.presignedGetUrl(eq("https://bucket/rooms/a.png"), eq(Duration.ofHours(1))))
                .thenReturn("https://signed/a.png?sig=1");
        var event = MessageEventFixtures.event(MessageEventType.CREATED, 7L, 11L, 3L, 1L, null, false);
        event.setImageUrl("https://bucket/rooms/a.png");

        MessageResponse response = new MessageResponseFactory(s3Service).of(event);

        assertThat(response.getImageUrl()).isEqualTo("https://signed/a.png?sig=1");
    }
}
