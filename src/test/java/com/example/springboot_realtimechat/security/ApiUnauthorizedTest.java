package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰이 만료되면 API가 로그인 페이지로 리다이렉트한다. 브라우저 fetch는 그걸 cross-origin에서
// 막아 네트워크 오류로 만들고, 사용자는 세션이 끝난 줄 모른다. API는 401 JSON을 줘야 한다.
@SpringBootTest
@AutoConfigureMockMvc
class ApiUnauthorizedTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 미인증_API_요청은_401과_코드를_받는다() throws Exception {
        mockMvc.perform(get("/api/chatrooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 소셜_로그인_진입은_리다이렉트를_유지한다() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection());
    }
}
