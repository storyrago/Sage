package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.image.event.MessageImageCleanupConsumer;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.outbox.MessageEventTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.message.delivery=kafka",
        "app.message.node-id=test-node",
        "app.outbox.enabled=true",
        "app.outbox.schema-registry-url=mock://" + ImageCleanupConsumerWiringTest.SCOPE,
        "spring.kafka.listener.auto-startup=false"
})
class ImageCleanupConsumerWiringTest {

    static final String SCOPE = "image-cleanup-wiring-test";

    static {
        MessageEventTestSupport.register(SCOPE);
    }

    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired CommonErrorHandler commonErrorHandler;

    @Test
    void 이미지_정리는_서버_전체에서_하나의_group으로_읽는다() {
        // 태깅은 한 번이면 된다. 노드 id를 붙이면 서버 수만큼 태깅한다.
        assertThat(listenerRegistry.getListenerContainer("imageCleanup").getGroupId())
                .isEqualTo(MessageImageCleanupConsumer.GROUP_ID);
    }

    @Test
    void 재시도_토픽_3단계와_전용_DLT를_구독한다() {
        // 앱은 토픽을 만들지 않는다. 이름이 create-topics.sh와 어긋나면 운영에서 구독·발행이 실패한다.
        List<String> topics = listenerRegistry.getListenerContainers().stream()
                .flatMap(container -> Arrays.stream(container.getContainerProperties().getTopics()))
                .filter(topic -> topic.startsWith(MessageEventSchema.TOPIC + ".image-cleanup"))
                .toList();
        List<String> expected = new ArrayList<>(MessageImageCleanupConsumer.RETRY_TOPICS);
        expected.add(MessageImageCleanupConsumer.DEAD_LETTER_TOPIC);

        assertThat(topics).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 순서_보장_소비자의_에러_핸들러가_아니라_재시도_토픽용_핸들러를_쓴다() {
        assertThat(((AbstractMessageListenerContainer<?, ?>) listenerRegistry.getListenerContainer("imageCleanup"))
                .getCommonErrorHandler()).isNotNull().isNotSameAs(commonErrorHandler);
        // C1 소비자는 그대로 같은 빈을 쓴다.
        assertThat(((AbstractMessageListenerContainer<?, ?>) listenerRegistry.getListenerContainer("realtimeDelivery"))
                .getCommonErrorHandler()).isSameAs(commonErrorHandler);
    }
}
