package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.SpringbootRealtimechatApplication;
import com.example.springboot_realtimechat.domain.image.event.MessageImageCleanupConsumer;
import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.event.SchemaRegistryUnavailableException;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.support.KafkaUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDC 통합 테스트용 앱 서버(노드)를 Kafka 전달 모드로 띄운다.
 * S3 클라이언트는 기록용 가짜로 바꾸고(실제 AWS에 접속하지 않는다), 이벤트 읽기에 레지스트리 장애를 주입할 수 있게 감싼다.
 * 대역은 컴포넌트 스캔에 걸리지 않도록 @Configuration이 아니라 초기화기로 등록한다.
 */
public final class KafkaTestNodes {

    private KafkaTestNodes() {
    }

    public static ConfigurableApplicationContext start(String nodeId, String... extraArgs) {
        // 서명 URL은 자격 증명만 있으면 로컬에서 계산된다(접속하지 않음). 이미지 메시지를 실시간 전달할 때 필요하다.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + CdcTestEnvironment.jdbcUrl(),
                "--spring.datasource.username=" + CdcTestEnvironment.username(),
                "--spring.datasource.password=" + CdcTestEnvironment.password(),
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--app.outbox.enabled=true",
                "--app.outbox.schema-registry-url=" + CdcTestEnvironment.schemaRegistryUrl(),
                "--app.message.delivery=kafka",
                "--app.message.node-id=" + nodeId,
                "--spring.kafka.bootstrap-servers=" + CdcTestEnvironment.bootstrapServers(),
                // 운영은 latest. 테스트는 할당 직후 위치를 잡는 사이에 보낸 이벤트를 놓치지 않도록 처음부터 읽는다.
                // 앞선 테스트의 이벤트도 받지만, 검증은 각 테스트가 만든 방·키만 본다.
                "--spring.kafka.consumer.auto-offset-reset=earliest"));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(SpringbootRealtimechatApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers((ApplicationContextInitializer<ConfigurableApplicationContext>) context ->
                        context.getBeanFactory().addBeanPostProcessor(new TestDoubles()))
                .run(args.toArray(String[]::new));
    }

    private static final class TestDoubles implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof S3Client original) {
                original.close();
                return new RecordingS3();
            }
            if (bean instanceof MessageEventReader reader) {
                return new OutageInjectingReader(reader);
            }
            return bean;
        }
    }

    /** 키별 태깅 시도 횟수를 센다. 모든 노드가 같은 기록을 공유한다(같은 JVM). */
    public static final class RecordingS3 implements S3Client {

        private static final Map<String, AtomicInteger> TAGGINGS = new ConcurrentHashMap<>();
        private static final Set<String> FAILING_KEYS = ConcurrentHashMap.newKeySet();

        public static int taggings(String key) {
            AtomicInteger count = TAGGINGS.get(key);
            return count == null ? 0 : count.get();
        }

        /** 이 키의 태깅은 매번 실패한다(S3 장애 흉내). */
        public static void failFor(String key) {
            FAILING_KEYS.add(key);
        }

        @Override
        public PutObjectTaggingResponse putObjectTagging(PutObjectTaggingRequest request) {
            TAGGINGS.computeIfAbsent(request.key(), k -> new AtomicInteger()).incrementAndGet();
            if (FAILING_KEYS.contains(request.key())) {
                throw S3Exception.builder().message("테스트: S3 불통").statusCode(503).build();
            }
            return PutObjectTaggingResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }
    }

    /** 이미지 정리 group이 특정 이미지의 삭제 이벤트를 읽을 때만 정해진 횟수만큼 레지스트리 불통을 낸다. */
    public static final class RegistryOutage {

        private static final Map<String, AtomicInteger> REMAINING = new ConcurrentHashMap<>();
        private static final Map<String, AtomicInteger> THROWN = new ConcurrentHashMap<>();

        public static void simulate(String imageUrl, int times) {
            REMAINING.put(imageUrl, new AtomicInteger(times));
            THROWN.put(imageUrl, new AtomicInteger());
        }

        public static int thrown(String imageUrl) {
            AtomicInteger count = THROWN.get(imageUrl);
            return count == null ? 0 : count.get();
        }

        static void maybeThrow(MessageEvent event) {
            String url = event.getDereferencedImageUrl();
            String group = KafkaUtils.getConsumerGroupId();
            if (url == null || group == null || !group.startsWith(MessageImageCleanupConsumer.GROUP_ID)) {
                return;
            }
            AtomicInteger remaining = REMAINING.get(url);
            if (remaining != null && remaining.getAndDecrement() > 0) {
                THROWN.get(url).incrementAndGet();
                throw new SchemaRegistryUnavailableException("테스트: 레지스트리 불통", new SocketTimeoutException("timeout"));
            }
        }
    }

    private static final class OutageInjectingReader extends MessageEventReader {

        private final MessageEventReader delegate;

        OutageInjectingReader(MessageEventReader delegate) {
            super(null);
            this.delegate = delegate;
        }

        @Override
        public MessageEvent read(byte[] payload) {
            MessageEvent event = delegate.read(payload);
            RegistryOutage.maybeThrow(event);
            return event;
        }
    }
}
