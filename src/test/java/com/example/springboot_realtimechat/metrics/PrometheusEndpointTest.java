package com.example.springboot_realtimechat.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트 설정(application.yaml 통째 대체)은 exposure.include를 갖고 있지 않으므로 이 테스트에서 직접 켠다.
// 로컬 Prometheus가 인증 없이 /actuator/prometheus를 긁을 수 있는지, 레지스트리가 실제로 붙어 있는지 확인한다.
@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,prometheus")
@AutoConfigureMockMvc
class PrometheusEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void prometheus_엔드포인트는_인증_없이_200을_받는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("jvm_memory_used_bytes");
    }
}
