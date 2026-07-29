package com.example.springboot_realtimechat.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 배포 파이프라인이 기동 성공을 판정하는 근거다. 인증 없이 열려 있어야 컨테이너 헬스체크가 동작한다.
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 헬스는_인증_없이_UP을_반환한다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
