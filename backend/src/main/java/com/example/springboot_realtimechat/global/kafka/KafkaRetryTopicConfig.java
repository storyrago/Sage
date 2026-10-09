package com.example.springboot_realtimechat.global.kafka;

import com.example.springboot_realtimechat.domain.message.event.SchemaRegistryUnavailableException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;

/**
 * 재시도 토픽을 쓰는 소비자(이미지 정리)의 공통 설정.
 * 레지스트리 불통은 이벤트 잘못이 아니므로 재시도 토픽·DLT로 넘기지 않고 그 자리에서 복구될 때까지 다시 시도한다
 * (C1 순서 보장 소비자와 같은 정책·백오프). 넘기면 장애 동안의 모든 이벤트가 재시도 단계를 거쳐 DLT로 쏟아진다.
 */
@Configuration
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
public class KafkaRetryTopicConfig extends RetryTopicConfigurationSupport {

    @Override
    protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
        blockingRetries
                .retryOn(SchemaRegistryUnavailableException.class)
                .backOff(KafkaDeliveryConfig.registryOutageBackOff());
    }
}
