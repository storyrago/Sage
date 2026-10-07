package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.domain.message.service.MessageService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

// 순번·재전송 보장은 MySQL InnoDB의 행 잠금과 REPEATABLE READ 스냅샷에 기대므로 실제 MySQL로 검증한다.
// 클래스 레벨 @Transactional을 걸지 않는다 — 스레드마다 독립 트랜잭션으로 커밋돼야 경합이 재현된다.
@SpringBootTest
class MessageWriteConcurrencyMySqlTest {

    // 스프링 컨텍스트가 접속 정보를 읽기 전에 컨테이너가 떠 있어야 한다. 정리는 Testcontainers(Ryuk)가 한다.
    private static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0"));

    static {
        mysql.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl());
        registry.add("spring.datasource.username", () -> mysql.getUsername());
        registry.add("spring.datasource.password", () -> mysql.getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomRepository chatRoomRepository;

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    @Test
    void 동시에_보내도_순번이_겹치거나_비지_않는다() throws Exception {
        Member member = memberService.create("seq-concurrency@e.com", "1234", "동시순번");
        ChatRoom room = chatRoomService.create("동시순번방", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);
        int threads = 8;
        int perThread = 10;

        runConcurrently(threads, () -> {
            for (int i = 0; i < perThread; i++) {
                messageService.create("m", null, member.getId(), room.getId(), null);
            }
            return null;
        });

        List<Long> seqs = messageRepository.findLatestByChatRoom(room, PageRequest.of(0, 200)).stream()
                .map(Message::getSeq)
                .sorted()
                .toList();
        long total = (long) threads * perThread;
        assertThat(seqs).containsExactlyElementsOf(LongStream.rangeClosed(1, total).boxed().toList());
        assertThat(chatRoomRepository.findById(room.getId()).orElseThrow().getLastMessageSeq()).isEqualTo(total);
    }
}
