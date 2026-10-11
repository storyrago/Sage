package com.example.springboot_realtimechat.measure;

import com.example.springboot_realtimechat.SpringbootRealtimechatApplication;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** 측정용 앱 서버를 띄운다. 실제 MySQL·Kafka·레지스트리 컨테이너에 붙고, 텍스트 메시지만 보낸다. */
final class MeasureNodes {

    private MeasureNodes() {
    }

    static ConfigurableApplicationContext start(String nodeId) {
        // 서명 URL 계산에 필요한 자격 증명(접속하지 않는다). 텍스트 메시지만 보내므로 S3는 호출되지 않는다.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        return new SpringApplicationBuilder(SpringbootRealtimechatApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + CdcTestEnvironment.jdbcUrl(),
                        "--spring.datasource.username=" + CdcTestEnvironment.username(),
                        "--spring.datasource.password=" + CdcTestEnvironment.password(),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        // 테스트 설정은 SQL을 출력한다. 수백 건을 보내는 측정에서는 출력 자체가 부하가 된다.
                        "--spring.jpa.show-sql=false",
                        "--spring.jpa.properties.hibernate.format_sql=false",
                        "--app.outbox.schema-registry-url=" + CdcTestEnvironment.schemaRegistryUrl(),
                        "--app.message.node-id=" + nodeId,
                        "--spring.kafka.bootstrap-servers=" + CdcTestEnvironment.bootstrapServers(),
                        // 할당 직후 위치를 잡는 사이에 보낸 이벤트를 놓치지 않도록 처음부터 읽는다(KafkaTestNodes와 같은 이유).
                        "--spring.kafka.consumer.auto-offset-reset=earliest",
                        // 테스트 설정은 일반 테스트를 위해 소비자를 끈다. 측정 앱은 실제로 소비해야 한다(KafkaTestNodes와 같은 이유).
                        "--spring.kafka.listener.auto-startup=true",
                        "--spring.data.redis.host=" + MeasureEnvironment.redisHost(),
                        "--spring.data.redis.port=" + MeasureEnvironment.redisPort());
    }

    static int port(ConfigurableApplicationContext node) {
        return Integer.parseInt(node.getEnvironment().getRequiredProperty("local.server.port"));
    }

    /** 로그인 API(시도 횟수 제한 있음)를 거치지 않고 같은 서명 키로 토큰을 만든다. */
    static String token(ConfigurableApplicationContext node, Member member) {
        return node.getBean(JwtTokenProvider.class).createAccessToken(member.getId(), member.getEmail());
    }
}
