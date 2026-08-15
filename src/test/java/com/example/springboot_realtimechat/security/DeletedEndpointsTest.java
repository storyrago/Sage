package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 인가를 붙이는 대신 지운 경로가 다시 살아나지 않게 고정한다. */
@SpringBootTest
class DeletedEndpointsTest {

    // actuator가 controllerEndpointHandlerMapping을 같은 타입으로 함께 등록해 모호해진다.
    // 우리 @RestController들을 매핑하는 기본 빈 이름으로 한정한다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /** "GET /api/members" 형태의 문자열 집합 */
    private Set<String> mappedEndpoints() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(this::describe)
                .collect(Collectors.toSet());
    }

    private java.util.stream.Stream<String> describe(RequestMappingInfo info) {
        Set<String> patterns = info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
        return patterns.stream().flatMap(pattern ->
                info.getMethodsCondition().getMethods().stream()
                        .map(method -> method.name() + " " + pattern));
    }

    @Test
    void 전체_회원_목록_경로가_없다() {
        assertThat(mappedEndpoints()).doesNotContain("GET /api/members");
    }

    @Test
    void 방_단건_조회_경로가_없다() {
        assertThat(mappedEndpoints()).doesNotContain("GET /api/chatrooms/{id}");
    }

    @Test
    void 남아있어야_할_경로는_그대로다() {
        assertThat(mappedEndpoints())
                .contains("GET /api/members/{id}", "GET /api/members/me", "GET /api/chatrooms");
    }
}
