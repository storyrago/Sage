package com.example.springboot_realtimechat.outbox;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 테스트 기본 모의 레지스트리(mock://sage-test)에 스키마를 등록한다.
 * OutboxConfig는 기동할 때 스키마 등록 여부를 확인하므로, 컨텍스트가 빈을 만들기 전에 등록돼 있어야 한다.
 * META-INF/spring.factories로 모든 테스트 컨텍스트에 걸린다. 다른 scope를 쓰는 테스트에는 영향이 없다.
 */
public class DefaultSchemaRegistryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String SCOPE = "sage-test";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        MessageEventTestSupport.register(SCOPE);
    }
}
