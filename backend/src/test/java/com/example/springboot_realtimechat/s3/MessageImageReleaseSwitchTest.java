package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.image.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.domain.image.event.MessageImageRelease;
import com.example.springboot_realtimechat.domain.image.event.OutboxMessageImageRelease;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.outbox.OutboxWriter;
import com.example.springboot_realtimechat.outbox.MessageEventTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// Kafka 경로에서는 메시지 이미지 정리를 삭제 이벤트 소비자가 맡는다. 프로필 사진은 메시지 이벤트가 아니라 기존 경로 그대로다.
@SpringBootTest(properties = {
        "app.message.delivery=kafka",
        "app.message.node-id=test-node",
        "app.outbox.enabled=true",
        "app.outbox.schema-registry-url=mock://" + MessageImageReleaseSwitchTest.SCOPE,
        "spring.kafka.listener.auto-startup=false"
})
@Transactional
@RecordApplicationEvents
class MessageImageReleaseSwitchTest {

    static final String SCOPE = "image-release-switch-test";
    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    static {
        MessageEventTestSupport.register(SCOPE);
    }

    @MockitoBean OutboxWriter outboxWriter;
    @Autowired MessageImageRelease messageImageRelease;
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ApplicationEvents events;

    @Test
    void Kafka_경로에서는_메시지_이미지를_지워도_커밋_후_정리_이벤트를_발행하지_않는다() {
        assertThat(messageImageRelease).isInstanceOf(OutboxMessageImageRelease.class);
        Member author = memberService.create("release-kafka@e.com", "1234", "정리");
        ChatRoom room = chatRoomService.create("정리경로방", false, null);
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        String url = BUCKET_PREFIX + "rooms/" + author.getId() + "/00000000-0000-0000-0000-0000000000b1_a.png";
        Message message = messageService.create("", url, author.getId(), room.getId(), null);

        messageService.delete(room.getId(), message.getId(), author.getId());

        assertThat(events.stream(ImageDereferencedEvent.class)).isEmpty();
    }

    @Test
    void Kafka_경로에서도_프로필_사진_교체는_커밋_후_정리_이벤트를_발행한다() {
        Member member = memberService.create("release-profile@e.com", "1234", "프로필");
        String old = BUCKET_PREFIX + "profiles/" + member.getId() + "/00000000-0000-0000-0000-0000000000b2_old.png";
        String neu = BUCKET_PREFIX + "profiles/" + member.getId() + "/00000000-0000-0000-0000-0000000000b3_new.png";
        memberService.updateProfileImage(member.getId(), old);

        memberService.updateProfileImage(member.getId(), neu);

        assertThat(events.stream(ImageDereferencedEvent.class).map(ImageDereferencedEvent::url)).containsExactly(old);
    }
}
