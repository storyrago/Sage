package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.SpringbootRealtimechatApplication;
import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.controller.ChatMessageController;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메시지 저장(id 부여)과 Redis 발행 사이에는 순서를 보장하는 락이 없다.
 * 두 앱 노드가 각자 저장 후 발행하면, "늦게 저장해 id가 큰 메시지"가
 * "먼저 저장해 id가 작은 메시지"보다 먼저 발행될 수 있다 — 이 경우 모든 구독 노드가
 * 메시지를 id 역순으로 받는다. 프론트는 들어온 메시지의 id가 방의 현재 최대 id보다
 * 작으면 "아직 못 불러온 과거 메시지의 수정"으로 간주해 무시하므로(App.tsx ~417줄),
 * 이 역전이 실제로 일어나면 더 작은 id의 메시지가 실시간 화면에서 사라진다.
 * 이 테스트는 그 역전이 실제로 재현되는 것을 실제 Redis로 고정한다.
 */
class CrossNodeMessageOrderTest {

    private static GenericContainer<?> redis;
    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;
    private static RecordingListener recorderA;
    private static RecordingListener recorderB;

    private static Member author;
    private static Long roomId;

    @BeforeAll
    static void startNodesAndRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();

        // 운영에서는 DB가 하나뿐이라 id는 같은 시퀀스에서 나온다.
        // 같은 JVM 안에서 H2 named in-memory DB를 공유하려면 DB_CLOSE_DELAY=-1이 필요하다.
        String sharedDbUrl = "jdbc:h2:mem:crossnode;MODE=MySQL;DB_CLOSE_DELAY=-1";

        // A가 먼저 스키마를 만들고(create-drop), B는 그 스키마를 그대로 쓴다(none).
        // 순서를 바꾸면 B가 기동할 때 테이블이 없어 실패한다.
        nodeA = startNode(sharedDbUrl, "create-drop");
        nodeB = startNode(sharedDbUrl, "none");

        recorderA = attachRecorder(nodeA);
        recorderB = attachRecorder(nodeB);

        MemberService memberService = nodeA.getBean(MemberService.class);
        ChatRoomService chatRoomService = nodeA.getBean(ChatRoomService.class);
        ChatRoomMemberService chatRoomMemberService = nodeA.getBean(ChatRoomMemberService.class);

        author = memberService.create("cross-node-author@e.com", "1234", "크로스노드발신자");
        ChatRoom room = chatRoomService.create("크로스노드방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
    }

    @AfterAll
    static void stopNodesAndRedis() {
        if (nodeB != null) nodeB.close();
        if (nodeA != null) nodeA.close();
        if (redis != null) redis.stop();
    }

    private static ConfigurableApplicationContext startNode(String dbUrl, String ddlAuto) {
        // 리스너 디스패치용 executor를 단일 스레드로 고정한다(SingleThreadedRedisDispatch).
        // RedisMessageListenerContainer의 기본 executor는 메시지마다 새 스레드를 띄우는 방식이라,
        // 그 스레드 스케줄링 자체의 흔들림이 "저장↔발행 순서" 경쟁과 뒤섞여 관찰을 어렵게 한다.
        SpringApplicationBuilder builder = new SpringApplicationBuilder(
                SpringbootRealtimechatApplication.class, SingleThreadedRedisDispatch.class)
                .web(WebApplicationType.SERVLET);
        // run()의 커맨드라인 인자는 application.yaml보다 우선순위가 높다 — 그래야 공유 DB·Redis로 덮어써진다.
        return builder.run(
                "--server.port=0",
                "--spring.datasource.url=" + dbUrl,
                "--spring.jpa.hibernate.ddl-auto=" + ddlAuto,
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getFirstMappedPort());
    }

    /**
     * RedisConfig.java(프로덕션 코드)는 그대로 두고, 테스트 컨텍스트에만 추가되는 설정이다.
     * RedisMessageListenerContainer가 만들어진 직후 디스패치 executor를 단일 스레드로 바꿔,
     * 리스너 호출 순서가 실제 소켓 수신 순서와 같아지도록 고정한다.
     */
    @Configuration
    static class SingleThreadedRedisDispatch {
        @Bean
        static BeanPostProcessor pinRedisDispatchToSingleThread() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof RedisMessageListenerContainer container) {
                        ExecutorService singleThread = Executors.newSingleThreadExecutor();
                        container.setTaskExecutor(singleThread);
                        container.setSubscriptionExecutor(singleThread);
                    }
                    return bean;
                }
            };
        }
    }

    // RedisSubscriber와 같은 방식으로 역직렬화해, 각 노드가 "실제로 수신한" 순서를 기록한다.
    // 프로덕션 코드(RedisSubscriber)는 건드리지 않고, 같은 토픽에 리스너를 하나 더 얹을 뿐이다.
    private static RecordingListener attachRecorder(ConfigurableApplicationContext context) {
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        RecordingListener recorder = new RecordingListener(objectMapper);
        RedisMessageListenerContainer container = context.getBean(RedisMessageListenerContainer.class);
        ChannelTopic chatroomTopic = context.getBean("channelTopic", ChannelTopic.class);
        container.addMessageListener(recorder, chatroomTopic);
        return recorder;
    }

    @Test
    void 늦게_저장된_메시지가_먼저_발행되면_모든_노드가_id_역순으로_받는다() {
        recorderA.receivedMessageIds.clear();
        recorderB.receivedMessageIds.clear();

        MessageService messageServiceA = nodeA.getBean(MessageService.class);
        MessageService messageServiceB = nodeB.getBean(MessageService.class);
        MessageResponseFactory factoryA = nodeA.getBean(MessageResponseFactory.class);
        MessageResponseFactory factoryB = nodeB.getBean(MessageResponseFactory.class);
        RedisPublisher publisherA = nodeA.getBean(RedisPublisher.class);
        RedisPublisher publisherB = nodeB.getBean(RedisPublisher.class);

        // 저장은 A(msg1)가 먼저, B(msg2)가 나중 — id는 저장 순서대로 매겨진다.
        Message msg1 = messageServiceA.create("A가 저장한 메시지", null, author.getId(), roomId, null);
        Message msg2 = messageServiceB.create("B가 저장한 메시지", null, author.getId(), roomId, null);
        assertThat(msg1.getId()).isLessThan(msg2.getId());

        // 발행은 B(msg2)가 먼저, A(msg1)가 나중 — 저장 순서와 발행 순서가 뒤집힌 경쟁 상황을 그대로 만든다.
        publisherB.publish(factoryB.of(msg2));
        publisherA.publish(factoryA.of(msg1));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(recorderA.receivedMessageIds).hasSize(2);
            assertThat(recorderB.receivedMessageIds).hasSize(2);
        });

        // 두 노드 모두 id가 큰 msg2를 먼저, id가 작은 msg1을 나중에 받는다 — DB id 순서와 반대.
        assertThat(recorderA.receivedMessageIds).containsExactly(msg2.getId(), msg1.getId());
        assertThat(recorderB.receivedMessageIds).containsExactly(msg2.getId(), msg1.getId());
    }

    /**
     * 위 테스트는 경쟁 순서를 손으로 고정한 결정론적 재현이다.
     * 이 테스트는 실제 운영 경로(ChatMessageController.sendMessage)로 두 노드에서
     * 동시에 보낼 때 역전이 "실제로 얼마나" 일어나는지를 참고용으로 측정한다.
     * 역전 횟수 자체는 타이밍에 달려 있어 assert하지 않는다 — 모든 메시지가 수신됐는지만 확인한다.
     */
    @Test
    @EnabledIfSystemProperty(named = "measure", matches = "true")
    void 동시_발송시_노드별_수신_순서_역전_횟수를_측정한다() throws Exception {
        recorderA.receivedMessageIds.clear();
        recorderB.receivedMessageIds.clear();

        ChatMessageController controllerA = nodeA.getBean(ChatMessageController.class);
        ChatMessageController controllerB = nodeB.getBean(ChatMessageController.class);

        CustomUserDetails userDetails = new CustomUserDetails(author.getId(), author.getEmail());
        Authentication principal = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());

        int totalMessages = 200;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalMessages; i++) {
            int idx = i;
            ChatMessageController controller = (idx % 2 == 0) ? controllerA : controllerB;
            futures.add(executor.submit(() -> {
                MessageRequest request = new MessageRequest();
                request.setContent("동시발송 " + idx);
                controller.sendMessage(roomId, request, principal);
            }));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(recorderA.receivedMessageIds).hasSize(totalMessages);
            assertThat(recorderB.receivedMessageIds).hasSize(totalMessages);
        });

        // 역전 자체는 assert하지 않지만, 모든 메시지가 중복·누락 없이 도착했는지는 확인한다.
        assertThat(new HashSet<>(recorderA.receivedMessageIds)).hasSize(totalMessages);
        assertThat(new HashSet<>(recorderB.receivedMessageIds)).hasSize(totalMessages);

        System.out.println("[measure] nodeA 인접 역전 횟수 = " + countAdjacentInversions(recorderA.receivedMessageIds));
        System.out.println("[measure] nodeB 인접 역전 횟수 = " + countAdjacentInversions(recorderB.receivedMessageIds));
    }

    private static long countAdjacentInversions(List<Long> receivedIds) {
        long inversions = 0;
        for (int i = 1; i < receivedIds.size(); i++) {
            if (receivedIds.get(i) < receivedIds.get(i - 1)) {
                inversions++;
            }
        }
        return inversions;
    }

    private static class RecordingListener implements MessageListener {
        private final ObjectMapper objectMapper;
        final List<Long> receivedMessageIds = new CopyOnWriteArrayList<>();

        RecordingListener(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void onMessage(org.springframework.data.redis.connection.Message redisMessage, byte[] pattern) {
            MessageResponse response = objectMapper.readValue(redisMessage.getBody(), MessageResponse.class);
            receivedMessageIds.add(response.getMessageId());
        }
    }
}
