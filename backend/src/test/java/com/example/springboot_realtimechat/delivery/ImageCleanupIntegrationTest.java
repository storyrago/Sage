package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.image.event.MessageImageCleanupConsumer;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;
import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 두 대를 Kafka 전달 모드로 띄우고 실제 파이프라인(MySQL → Debezium → Kafka → 이미지 정리 소비자)으로 검증한다.
 * S3는 기록용 가짜(KafkaTestNodes.RecordingS3)라 어떤 키에 몇 번 태깅을 시도했는지 본다.
 */
@Tag("cdc")
class ImageCleanupIntegrationTest {

    // 재시도 간격만 줄인다(운영 기본 5초·30초·3분). 재시도 토픽 이름은 단계 번호로 붙어 간격과 무관하다.
    private static final String[] FAST_RETRY = {
            "--app.image-cleanup.retry.delay-ms=200",
            "--app.image-cleanup.retry.multiplier=2",
            "--app.image-cleanup.retry.max-delay-ms=1000"};

    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;

    @BeforeAll
    static void startNodes() {
        CdcTestEnvironment.start();
        nodeA = KafkaTestNodes.start("image-a", FAST_RETRY);
        nodeB = KafkaTestNodes.start("image-b", FAST_RETRY);
        // 단일 group이라 두 서버가 파티션을 나눠 갖는다.
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() ->
                assigned(nodeA) + assigned(nodeB) == CdcTestEnvironment.TOPIC_PARTITIONS);
    }

    @AfterAll
    static void stopNodes() {
        if (nodeB != null) nodeB.close();
        if (nodeA != null) nodeA.close();
    }

    private static int assigned(ConfigurableApplicationContext node) {
        Collection<TopicPartition> partitions = node.getBean(KafkaListenerEndpointRegistry.class)
                .getListenerContainer("imageCleanup").getAssignedPartitions();
        return partitions == null ? 0 : partitions.size();
    }

    private static Member joined(String email, ChatRoom room) {
        Member member = nodeA.getBean(MemberService.class).create(email, "1234", "정리");
        nodeA.getBean(ChatRoomMemberService.class).join(member.getId(), room.getId(), null);
        return member;
    }

    private static String chatImageUrl(Member author) {
        Environment env = nodeA.getEnvironment();
        return "https://" + env.getProperty("aws.s3.bucket") + ".s3." + env.getProperty("aws.s3.region")
                + ".amazonaws.com/rooms/" + author.getId() + "/" + UUID.randomUUID() + "_photo.png";
    }

    private static String keyOf(String url) {
        return nodeA.getBean(S3Service.class).extractKey(url);
    }

    private static Message imageMessage(ChatRoom room, Member author, String url) {
        return nodeA.getBean(MessageService.class).create("", url, author.getId(), room.getId(), null);
    }

    private static void delete(ChatRoom room, Member author, Message message) {
        nodeB.getBean(MessageService.class).delete(room.getId(), message.getId(), author.getId());
    }

    private static int taggings(String url) {
        return KafkaTestNodes.RecordingS3.taggings(keyOf(url));
    }

    @Test
    void 이미지_메시지를_지우면_두_서버_중_한_곳에서_한_번만_태깅한다() {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("정리단일방", false, null);
        Member author = joined("image-once@e.com", room);
        String url = chatImageUrl(author);
        Message message = imageMessage(room, author, url);

        delete(room, author, message);

        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> taggings(url) == 1);
        // 단일 group이라 다른 서버가 같은 이벤트를 또 처리하지 않는다.
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> taggings(url) == 1);
    }

    @Test
    void 다른_메시지가_같은_이미지를_아직_참조하면_태깅하지_않는다() {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("정리잔여참조방", false, null);
        Member author = joined("image-shared@e.com", room);
        String shared = chatImageUrl(author);
        String marker = chatImageUrl(author);
        Message first = imageMessage(room, author, shared);
        imageMessage(room, author, shared);                 // 같은 이미지를 다시 보낸 메시지가 남는다
        Message markerMessage = imageMessage(room, author, marker);

        delete(room, author, first);
        delete(room, author, markerMessage);

        // 같은 방 = 같은 파티션이라 차례로 처리된다. 표시용 이미지가 태깅됐으면 앞의 삭제도 처리가 끝났다.
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> taggings(marker) == 1);
        assertThat(taggings(shared)).isZero();
    }

    @Test
    void 태깅이_계속_실패하면_재시도_토픽_3단계를_거쳐_DLT로_간다() {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("정리실패방", false, null);
        Member author = joined("image-fail@e.com", room);
        String url = chatImageUrl(author);
        KafkaTestNodes.RecordingS3.failFor(keyOf(url));
        Message message = imageMessage(room, author, url);
        String roomKey = String.valueOf(room.getId());

        delete(room, author, message);

        assertThat(awaitRecordsWithKey(MessageImageCleanupConsumer.DEAD_LETTER_TOPIC, roomKey)).hasSize(1);
        assertThat(taggings(url)).isEqualTo(4);             // 첫 시도 + 재시도 3단계
        for (String retryTopic : MessageImageCleanupConsumer.RETRY_TOPICS) {
            assertThat(recordsWithKey(retryTopic, roomKey, Duration.ofSeconds(2))).as(retryTopic).hasSize(1);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> deadLetterCount(nodeA) + deadLetterCount(nodeB) >= 1);
    }

    @Test
    void 해석할_수_없는_이벤트는_재시도_토픽을_거치지_않고_바로_DLT로_간다() throws Exception {
        String key = "image-poison-" + UUID.randomUUID();
        byte[] poison = {1, 2, 3};
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CdcTestEnvironment.bootstrapServers()),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(MessageEventSchema.TOPIC, key, poison)).get();
        }

        List<ConsumerRecord<String, byte[]>> deadLetters =
                awaitRecordsWithKey(MessageImageCleanupConsumer.DEAD_LETTER_TOPIC, key);

        assertThat(deadLetters).singleElement().satisfies(r -> assertThat(r.value()).containsExactly(poison));
        assertThat(recordsWithKey(MessageImageCleanupConsumer.RETRY_TOPICS.get(0), key, Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void 레지스트리에_닿지_못하면_재시도_토픽으로_넘기지_않고_복구된_뒤_한_번_태깅한다() {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("정리레지스트리방", false, null);
        Member author = joined("image-registry@e.com", room);
        String url = chatImageUrl(author);
        KafkaTestNodes.RegistryOutage.simulate(url, 3);
        Message message = imageMessage(room, author, url);
        String roomKey = String.valueOf(room.getId());

        delete(room, author, message);

        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> taggings(url) == 1);
        assertThat(KafkaTestNodes.RegistryOutage.thrown(url)).isEqualTo(3);
        assertThat(recordsWithKey(MessageImageCleanupConsumer.RETRY_TOPICS.get(0), roomKey, Duration.ofSeconds(3))).isEmpty();
        assertThat(recordsWithKey(MessageImageCleanupConsumer.DEAD_LETTER_TOPIC, roomKey, Duration.ofSeconds(1))).isEmpty();
    }

    private static double deadLetterCount(ConfigurableApplicationContext node) {
        Counter counter = node.getBean(MeterRegistry.class).find(KafkaDeliveryConfig.DEAD_LETTER_COUNTER)
                .tag("group", MessageImageCleanupConsumer.GROUP_ID).counter();
        return counter == null ? 0 : counter.count();
    }

    private static KafkaConsumer<String, byte[]> fromBeginning(String topic) {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CdcTestEnvironment.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "image-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /** 이 키의 레코드가 하나라도 올 때까지 기다린 뒤, 그때까지 모은 것을 돌려준다. */
    private static List<ConsumerRecord<String, byte[]>> awaitRecordsWithKey(String topic, String key) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = fromBeginning(topic)) {
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ZERO).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) found.add(r);
                });
                return !found.isEmpty();
            });
        }
        return found;
    }

    /** 정해진 시간 동안 처음부터 읽어 이 키의 레코드를 모은다. 없으면 빈 목록. */
    private static List<ConsumerRecord<String, byte[]>> recordsWithKey(String topic, String key, Duration window) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = fromBeginning(topic)) {
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) found.add(r);
                });
            }
        }
        return found;
    }
}
