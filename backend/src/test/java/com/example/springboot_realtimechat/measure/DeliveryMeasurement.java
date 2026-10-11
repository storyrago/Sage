package com.example.springboot_realtimechat.measure;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 전파 모드를 같은 본문으로 잰다. 서버 2대(A·B)에 실제 STOMP 구독자를 하나씩 붙이고, REST로 보낸다.
 * 결과는 "[measure] ..." 줄로 출력하고, 보장을 주장하는 모드(kafka)만 단언한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DeliveryMeasurement {

    static final int ORDER_MESSAGES = 200;
    static final int ORDER_THREADS = 16;
    static final int OUTAGE_PHASE_MESSAGES = 20;
    // 복구 뒤 두 모드에 똑같이 주는 회복 시간. RedisMessageListenerContainer의 재구독 간격 기본값이 5초라 그 두 배.
    static final Duration RECOVERY_GRACE = Duration.ofSeconds(10);

    ConfigurableApplicationContext nodeA;
    ConfigurableApplicationContext nodeB;
    Member author;
    String authorTokenA;
    String authorTokenB;
    final List<StompSubscriber> openSubscribers = new ArrayList<>();
    private int memberSequence;

    /** app.message.delivery 값. */
    abstract String mode();

    /** 서버가 메시지를 받아 전달할 준비가 될 때까지 기다린다(kafka: 파티션 할당). */
    abstract void awaitReady(ConfigurableApplicationContext node);

    /** 전파 계층을 끊는다(redis: 앱↔Redis 연결, kafka: binlog→Kafka 커넥터). */
    abstract void cutPropagation() throws Exception;

    abstract void restorePropagation() throws Exception;

    /** 이 모드가 순서·무손실을 보장한다고 주장하는가 — 그렇다면 측정값을 단언한다. */
    abstract boolean assertsGuarantees();

    @BeforeAll
    void startNodes() throws Exception {
        MeasureEnvironment.start();
        nodeA = MeasureNodes.start("measure-" + mode() + "-a", mode());
        nodeB = MeasureNodes.start("measure-" + mode() + "-b", mode());
        awaitReady(nodeA);
        awaitReady(nodeB);
        author = newMember();
        authorTokenA = MeasureNodes.token(nodeA, author);
        authorTokenB = MeasureNodes.token(nodeB, author);
        warmUp();
    }

    @AfterEach
    void closeSubscribers() {
        openSubscribers.forEach(StompSubscriber::close);
        openSubscribers.clear();
    }

    @AfterAll
    void stopNodes() {
        if (nodeB != null) nodeB.close();
        if (nodeA != null) nodeA.close();
    }

    /** 첫 메시지가 두 구독자에게 도착할 때까지 기다린다. 커넥터·소비자·Redis 구독이 모두 살아 있다는 확인이다. */
    private void warmUp() throws Exception {
        Room room = newRoom();
        assertThat(RestSender.send(MeasureNodes.port(nodeA), authorTokenA, room.id(), "warm-up")).isEqualTo(200);
        awaitReceived(room.subscribers(), 1, Duration.ofSeconds(60));
        assertThat(room.subscribers()).allSatisfy(s -> assertThat(s.seqs()).containsExactly(1L));
        closeSubscribers();
    }

    @RepeatedTest(3)
    void 동시_전송의_수신_순서(RepetitionInfo repetition) throws Exception {
        Room room = newRoom();

        ExecutorService pool = Executors.newFixedThreadPool(ORDER_THREADS);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < ORDER_MESSAGES; i++) {
                int index = i;
                boolean toA = index % 2 == 0;
                results.add(pool.submit(() -> RestSender.send(
                        MeasureNodes.port(toA ? nodeA : nodeB), toA ? authorTokenA : authorTokenB,
                        room.id(), "order-" + index)));
            }
            int failures = 0;
            Map<Integer, Integer> statusCounts = new TreeMap<>();
            for (Future<Integer> result : results) {
                int status = result.get(60, TimeUnit.SECONDS);
                statusCounts.merge(status, 1, Integer::sum);
                if (status != 200) failures++;
            }

            long committed = committedMaxSeq(room.id());
            awaitReceived(room.subscribers(), committed, Duration.ofSeconds(60));

            List<OrderStats> stats = room.subscribers().stream()
                    .map(s -> OrderStats.of(s.seqs(), 1, committed))
                    .toList();
            System.out.printf("[measure] mode=%s scenario=order run=%d committed=%d restFailures=%d status=%s "
                            + "A{%s} B{%s}%n",
                    mode(), repetition.getCurrentRepetition(), committed, failures, statusCounts,
                    stats.get(0).summary(), stats.get(1).summary());

            if (assertsGuarantees()) {
                assertThat(failures).isZero();
                assertThat(committed).isEqualTo(ORDER_MESSAGES);
                assertThat(stats).allSatisfy(s -> assertThat(s.perfect()).as(s.summary()).isTrue());
            }
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
    }

    @RepeatedTest(3)
    void 전파_계층_일시_장애_중_유실(RepetitionInfo repetition) throws Exception {
        Room room = newRoom();

        // 1구간: 정상
        PhaseResult before = sendSequentially(room, "before");
        long endOfBefore = committedMaxSeq(room.id());
        awaitReceived(room.subscribers(), endOfBefore, Duration.ofSeconds(30));

        // 2구간: 전파 계층 차단 중
        PhaseResult during;
        cutPropagation();
        try {
            during = sendSequentially(room, "during");
        } finally {
            restorePropagation();
        }
        Thread.sleep(RECOVERY_GRACE.toMillis());

        // 3구간: 복구 후
        PhaseResult after = sendSequentially(room, "after");
        long committed = committedMaxSeq(room.id());
        // 늦은 커밋(클라이언트 타임아웃 뒤 서버가 뒤늦게 커밋한 건)을 놓치지 않으려고, 구간별 기대 seq는
        // MAX(seq) 스냅샷이 아니라 이 시점에 DB에 실제로 커밋된 본문 접두어로 읽는다.
        awaitReceived(room.subscribers(), committed, Duration.ofSeconds(60));

        List<Long> expectedBefore = expectedSeqsByPrefix(room.id(), "before-%");
        List<Long> expectedDuring = expectedSeqsByPrefix(room.id(), "during-%");
        List<Long> expectedAfter = expectedSeqsByPrefix(room.id(), "after-%");

        for (int i = 0; i < room.subscribers().size(); i++) {
            List<StompSubscriber.Received> items = room.subscribers().get(i).received();
            List<Long> seqs = items.stream().map(StompSubscriber.Received::seq).toList();
            OrderStats beforeStats = OrderStats.of(receivedByPrefix(items, "before-"), expectedBefore);
            OrderStats duringStats = OrderStats.of(receivedByPrefix(items, "during-"), expectedDuring);
            OrderStats afterStats = OrderStats.of(receivedByPrefix(items, "after-"), expectedAfter);
            OrderStats all = OrderStats.of(seqs, 1, committed);
            System.out.printf("[measure] mode=%s scenario=outage run=%d subscriber=%s committed=%d "
                            + "restFailures(before/during/after)=%d/%d/%d status(before/during/after)=%s/%s/%s "
                            + "before{%s} during{%s} after{%s} all{%s}%n",
                    mode(), repetition.getCurrentRepetition(), i == 0 ? "A" : "B", committed,
                    before.failures(), during.failures(), after.failures(),
                    before.statusCounts(), during.statusCounts(), after.statusCounts(),
                    beforeStats.summary(), duringStats.summary(), afterStats.summary(), all.summary());
            if (assertsGuarantees()) {
                assertThat(all.perfect()).as(all.summary()).isTrue();
            }
        }

        if (assertsGuarantees()) {
            assertThat(before.failures() + during.failures() + after.failures()).isZero();
            assertThat(committed).isEqualTo(3L * OUTAGE_PHASE_MESSAGES);
        }
    }

    /** 순차 전송 한 구간의 결과 — 실패 건수와 상태 코드 분포. */
    record PhaseResult(int failures, Map<Integer, Integer> statusCounts) {
    }

    /** 순차로 보낸다(seq = 보낸 순서). 두 서버에 번갈아 보내고, 실패 수와 상태 코드 분포를 돌려준다. */
    private PhaseResult sendSequentially(Room room, String phase) {
        int failures = 0;
        Map<Integer, Integer> statusCounts = new TreeMap<>();
        for (int i = 0; i < OUTAGE_PHASE_MESSAGES; i++) {
            boolean toA = i % 2 == 0;
            int status = RestSender.send(MeasureNodes.port(toA ? nodeA : nodeB),
                    toA ? authorTokenA : authorTokenB, room.id(), phase + "-" + i);
            statusCounts.merge(status, 1, Integer::sum);
            if (status != 200) failures++;
        }
        return new PhaseResult(failures, statusCounts);
    }

    /** 받은 것 중 본문이 그 구간 접두어로 시작하는 것의 seq만 도착 순서대로 뽑는다. */
    private static List<Long> receivedByPrefix(List<StompSubscriber.Received> items, String prefix) {
        return items.stream()
                .filter(r -> r.content().startsWith(prefix))
                .map(StompSubscriber.Received::seq)
                .toList();
    }

    /** DB에 실제로 커밋된, 본문이 이 접두어(LIKE 패턴)로 시작하는 메시지의 seq를 seq 순으로 읽는다. */
    private static List<Long> expectedSeqsByPrefix(long roomId, String contentLikePattern) throws Exception {
        try (Connection c = DriverManager.getConnection(
                     CdcTestEnvironment.jdbcUrl(), CdcTestEnvironment.username(), CdcTestEnvironment.password());
             PreparedStatement s = c.prepareStatement(
                     "SELECT seq FROM messages WHERE chatroom_id = ? AND content LIKE ? ORDER BY seq")) {
            s.setLong(1, roomId);
            s.setString(2, contentLikePattern);
            try (ResultSet rs = s.executeQuery()) {
                List<Long> seqs = new ArrayList<>();
                while (rs.next()) {
                    seqs.add(rs.getLong(1));
                }
                return seqs;
            }
        }
    }

    // ---- 공용 도우미 ----

    record Room(long id, List<StompSubscriber> subscribers) {
    }

    /** 새 방을 만들고 작성자와 구독자 2명을 넣는다. 구독자 A는 서버 A, B는 서버 B에 접속해 구독까지 마친다. */
    Room newRoom() throws Exception {
        ChatRoom room = nodeA.getBean(ChatRoomService.class).create("측정방-" + UUID.randomUUID(), false, null);
        ChatRoomMemberService members = nodeA.getBean(ChatRoomMemberService.class);
        members.join(author.getId(), room.getId(), null);
        Member readerA = newMember();
        Member readerB = newMember();
        members.join(readerA.getId(), room.getId(), null);
        members.join(readerB.getId(), room.getId(), null);

        StompSubscriber subscriberA = StompSubscriber.connect(
                MeasureNodes.port(nodeA), MeasureNodes.token(nodeA, readerA), room.getId());
        openSubscribers.add(subscriberA);
        StompSubscriber subscriberB = StompSubscriber.connect(
                MeasureNodes.port(nodeB), MeasureNodes.token(nodeB, readerB), room.getId());
        openSubscribers.add(subscriberB);
        String destination = "/sub/chatrooms/" + room.getId();
        awaitSubscribed(nodeA, destination);
        awaitSubscribed(nodeB, destination);
        return new Room(room.getId(), List.of(subscriberA, subscriberB));
    }

    /** 서버의 사용자 레지스트리에 구독이 올라갈 때까지 기다린다(SUBSCRIBE는 비동기라 바로 보내면 앞 메시지를 놓친다). */
    private static void awaitSubscribed(ConfigurableApplicationContext node, String destination) {
        SimpUserRegistry registry = node.getBean(SimpUserRegistry.class);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                !registry.findSubscriptions(s -> destination.equals(s.getDestination())).isEmpty());
    }

    private Member newMember() {
        memberSequence++;
        String email = "measure-" + mode() + "-" + memberSequence + "-" + UUID.randomUUID() + "@e.com";
        return nodeA.getBean(MemberService.class).create(email, "1234", "측정" + memberSequence);
    }

    /** DB에 커밋된 이 방의 최대 seq. 순번은 1부터 빈틈없이 발급되므로 커밋 건수와 같다. */
    static long committedMaxSeq(long roomId) throws Exception {
        try (Connection c = DriverManager.getConnection(
                     CdcTestEnvironment.jdbcUrl(), CdcTestEnvironment.username(), CdcTestEnvironment.password());
             PreparedStatement s = c.prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE chatroom_id = ?")) {
            s.setLong(1, roomId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 모든 구독자가 기대 건수 이상을 받거나 시간이 다 될 때까지 기다린다. 시간이 다 돼도 실패하지 않는다 —
     * 유실은 측정 대상이고, 판정은 OrderStats가 한다. 다 받은 뒤 1초 더 기다려 늦게 오는 중복도 잡는다.
     */
    static void awaitReceived(List<StompSubscriber> subscribers, long expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (subscribers.stream().allMatch(s -> s.seqs().size() >= expected)) {
                Thread.sleep(1000);
                return;
            }
            Thread.sleep(100);
        }
    }
}
