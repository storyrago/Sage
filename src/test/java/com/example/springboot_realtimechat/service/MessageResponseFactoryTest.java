package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;

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
}
