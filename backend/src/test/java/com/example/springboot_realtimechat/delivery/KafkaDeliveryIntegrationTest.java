package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;
import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 두 대(A·B)를 Kafka 전달 모드로 띄우고 실제 파이프라인(MySQL → Debezium → Kafka → 각 서버 소비자)으로 검증한다.
 * 각 서버가 브로커 채널로 내보낸 STOMP 메시지를 가로채 기록한다(실제 세션 없이 "그 서버가 무엇을 보냈는지"를 본다).
 */
@Tag("cdc")
class KafkaDeliveryIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;
    private static BrokerRecorder sentByA;
    private static BrokerRecorder sentByB;

    @BeforeAll
    static void startNodes() {
        CdcTestEnvironment.start();
        nodeA = startNode("node-a");
        nodeB = startNode("node-b");
        sentByA = BrokerRecorder.attach(nodeA);
        sentByB = BrokerRecorder.attach(nodeB);
        awaitAssigned(nodeA);
        awaitAssigned(nodeB);
    }

    @AfterAll
    static void stopNodes() {
        if (nodeB != null) nodeB.close();
        if (nodeA != null) nodeA.close();
    }

    private static ConfigurableApplicationContext startNode(String nodeId) {
        return KafkaTestNodes.start(nodeId);
    }

    private static void awaitAssigned(ConfigurableApplicationContext node) {
        KafkaListenerEndpointRegistry registry = node.getBean(KafkaListenerEndpointRegistry.class);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() ->
                registry.getListenerContainer("realtimeDelivery").getAssignedPartitions().size() == CdcTestEnvironment.TOPIC_PARTITIONS
                        && registry.getListenerContainer("unreadNotification").getAssignedPartitions().size() == CdcTestEnvironment.TOPIC_PARTITIONS);
    }

    /** 실제 STOMP 접속 없이, 이 서버에 해당 회원이 접속한 것처럼 사용자 레지스트리에 올린다. */
    private static void connect(ConfigurableApplicationContext node, Member member) {
        String name = String.valueOf(member.getId());
        Principal principal = () -> name;
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
        accessor.setSessionId("session-" + name + "-" + UUID.randomUUID());
        accessor.setUser(principal);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        node.publishEvent(new SessionConnectedEvent(node, message, principal));
        assertThat(node.getBean(SimpUserRegistry.class).getUser(name)).isNotNull();
    }

    private static Member joined(String email, ChatRoom room) {
        Member member = nodeA.getBean(MemberService.class).create(email, "1234", "통합");
        nodeA.getBean(ChatRoomMemberService.class).join(member.getId(), room.getId(), null);
        return member;
    }

    @Test
    void 두_서버가_모두_방_메시지를_순번_순서대로_보낸다() throws Exception {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("kafka순서방", false, null);
        Member author = joined("kafka-order@e.com", room);
        int count = 20;

        // 두 서버에서 동시에 저장한다 — 저장한 서버와 무관하게 모든 서버가 같은 순서로 받아야 한다.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            MessageService service = (t % 2 == 0 ? nodeA : nodeB).getBean(MessageService.class);
            futures.add(pool.submit(() -> {
                for (int i = 0; i < count / 4; i++) {
                    service.create("m", null, author.getId(), room.getId(), null);
                }
            }));
        }
        for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        String destination = "/sub/chatrooms/" + room.getId();
        List<Long> expected = LongStream.rangeClosed(1, count).boxed().toList();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(sentByA.seqsTo(destination)).containsExactlyElementsOf(expected);
            assertThat(sentByB.seqsTo(destination)).containsExactlyElementsOf(expected);
        });
    }

    @Test
    void 안읽음은_각_서버가_자기에게_접속한_멤버에게만_보낸다() {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("kafka안읽음방", false, null);
        Member sender = joined("kafka-unread-sender@e.com", room);
        Member onA = joined("kafka-unread-a@e.com", room);
        Member onB = joined("kafka-unread-b@e.com", room);
        Member offline = joined("kafka-unread-off@e.com", room);
        connect(nodeA, sender);
        connect(nodeA, onA);
        connect(nodeB, onB);

        nodeB.getBean(MessageService.class).create("안읽음", null, sender.getId(), room.getId(), null);

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(sentByA.destinations()).contains(unreadQueue(onA));
            assertThat(sentByB.destinations()).contains(unreadQueue(onB));
        });
        // 한 이벤트의 통지는 한 번의 처리에서 모두 나가므로, 위가 도착했으면 아래는 더 오지 않는다.
        assertThat(sentByA.destinations()).doesNotContain(unreadQueue(onB), unreadQueue(sender), unreadQueue(offline));
        assertThat(sentByB.destinations()).doesNotContain(unreadQueue(onA), unreadQueue(sender), unreadQueue(offline));
    }

    @Test
    void 해석할_수_없는_이벤트는_DLT로_보내고_같은_방의_다음_이벤트는_전달된다() throws Exception {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("kafkaDLT방", false, null);
        Member author = joined("kafka-dlt@e.com", room);
        String key = String.valueOf(room.getId());
        byte[] poison = {1, 2, 3};

        // 같은 키 = 같은 파티션. 독이 든 이벤트 뒤에 정상 이벤트가 온다.
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CdcTestEnvironment.bootstrapServers()),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(MessageEventSchema.TOPIC, key, poison)).get();
        }
        nodeA.getBean(MessageService.class).create("독 뒤의 메시지", null, author.getId(), room.getId(), null);

        String destination = "/sub/chatrooms/" + room.getId();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(sentByA.seqsTo(destination)).containsExactly(1L);
            assertThat(sentByB.seqsTo(destination)).containsExactly(1L);
        });

        // 두 서버 × 두 소비자 = 4건, 모두 원본 바이트 그대로.
        List<ConsumerRecord<String, byte[]>> deadLetters = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CdcTestEnvironment.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(MessageEventSchema.DEAD_LETTER_TOPIC));
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ZERO).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) deadLetters.add(r);
                });
                return deadLetters.size() >= 4;
            });
        }
        assertThat(deadLetters).allSatisfy(r -> {
            assertThat(r.value()).containsExactly(poison);
            assertThat(header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(MessageEventSchema.TOPIC);
        });
        assertThat(deadLetters).extracting(r -> header(r, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))
                .containsExactlyInAnyOrder("sage-realtime-node-a", "sage-unread-node-a",
                        "sage-realtime-node-b", "sage-unread-node-b");

        MeterRegistry meters = nodeA.getBean(MeterRegistry.class);
        assertThat(meters.get(KafkaDeliveryConfig.DEAD_LETTER_COUNTER).tag("group", "sage-realtime-node-a").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void 소비자_지표가_수집된다() {
        MeterRegistry meters = nodeA.getBean(MeterRegistry.class);
        // Kafka 클라이언트 지표(Boot KafkaMetricsAutoConfiguration)와 리스너 처리 시간(observation)
        assertThat(meters.find("kafka.consumer.fetch.manager.records.lag.max").meters()).isNotEmpty();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(meters.find("spring.kafka.listener").timers()).isNotEmpty());
    }

    private static String unreadQueue(Member member) {
        return "/user/" + member.getId() + "/queue/unread";
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 서버가 SimpMessagingTemplate으로 보낸 메시지(브로커 채널 입구)를 기록한다. 페이로드는 이미 JSON 바이트다. */
    static final class BrokerRecorder implements ChannelInterceptor {

        private final List<Message<?>> sent = new CopyOnWriteArrayList<>();

        static BrokerRecorder attach(ConfigurableApplicationContext node) {
            BrokerRecorder recorder = new BrokerRecorder();
            node.getBean("brokerChannel", AbstractSubscribableChannel.class).addInterceptor(recorder);
            return recorder;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            sent.add(message);
            return message;
        }

        List<String> destinations() {
            return sent.stream().map(m -> SimpMessageHeaderAccessor.getDestination(m.getHeaders())).toList();
        }

        List<Long> seqsTo(String destination) {
            return sent.stream()
                    .filter(m -> destination.equals(SimpMessageHeaderAccessor.getDestination(m.getHeaders())))
                    .map(m -> {
                        JsonNode body = JSON.readTree((byte[]) m.getPayload());
                        return body.get("seq").asLong();
                    })
                    .toList();
        }
    }
}
