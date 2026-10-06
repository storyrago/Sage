package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.controller.ChatMessageController;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

// sendMessage는 messageService.create(자체 @Transactional, 즉시 커밋) 후 redisPublisher.publish를 호출한다.
// publish가 실패해도 메시지는 이미 DB에 커밋된 상태로 남는다 — 저장은 됐지만 실시간으로는
// 아무에게도 전달되지 않는 이중 쓰기 간극을 재현한다. 트랜잭션 outbox 도입 시 이 테스트는 뒤집혀야 한다.
@SpringBootTest
// 클래스 레벨 @Transactional을 걸지 않는다. 걸면 테스트 트랜잭션이 전체를 감싸서 끝에 롤백되므로
// "DB에 실제로 커밋됐다"는 사실 자체를 검증할 수 없게 된다.
class MessagePublishFailureTest {

    @Autowired ChatMessageController chatMessageController;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @MockitoBean RedisPublisher redisPublisher;
    @MockitoBean S3Service s3Service;

    private Member author;
    private Long roomId;

    @Test
    void publish가_실패해도_메시지는_이미_커밋돼_있다() {
        author = memberService.create("publish-failure-author@e.com", "1234", "발신자");
        ChatRoom room = chatRoomService.create("발행실패방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);

        CustomUserDetails userDetails = new CustomUserDetails(author.getId(), author.getEmail());
        Authentication principal = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        MessageRequest request = new MessageRequest();
        request.setContent("발행 실패해도 저장되는 메시지");

        doThrow(new RuntimeException("Redis 발행 실패")).when(redisPublisher).publish(any());

        assertThatThrownBy(() -> chatMessageController.sendMessage(roomId, request, principal))
                .isInstanceOf(RuntimeException.class);

        verify(redisPublisher).publish(any());

        // 새 조회에서 메시지가 보여야 publish 전에 이미 커밋됐다는 증거가 된다.
        List<Message> messages = messageRepository.findLatestByChatRoom(room, PageRequest.of(0, 10));
        assertThat(messages)
                .extracting(Message::getContent)
                .contains("발행 실패해도 저장되는 메시지");
    }

    @AfterEach
    void tearDown() {
        if (roomId == null) {
            return;
        }
        ChatRoom room = chatRoomRepository.findById(roomId).orElse(null);
        if (room != null) {
            messageRepository.findLatestByChatRoom(room, PageRequest.of(0, 50))
                    .forEach(messageRepository::delete);
            chatRoomMemberRepository.findByChatRoom(room)
                    .forEach(chatRoomMemberRepository::delete);
            chatRoomRepository.delete(room);
        }
        if (author != null) {
            memberRepository.delete(author);
        }
    }
}
