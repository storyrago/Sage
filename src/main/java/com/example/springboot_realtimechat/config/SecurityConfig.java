package com.example.springboot_realtimechat.config;

import com.example.springboot_realtimechat.global.exception.ApiAuthenticationEntryPoint;
import com.example.springboot_realtimechat.security.CustomOidcUserService;
import com.example.springboot_realtimechat.security.HttpCookieOAuth2AuthorizationRequestRepository;
import com.example.springboot_realtimechat.security.JwtAuthenticationFilter;
import com.example.springboot_realtimechat.security.OAuth2FailureHandler;
import com.example.springboot_realtimechat.security.OAuth2SuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomOidcUserService customOidcUserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable()) // CSRF 끄기 (개발용)
                .sessionManagement(session->
                            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // /api/auth/**의 permitAll보다 먼저 등록해야 한다. 뒤에 두면 미인증 요청이 컨트롤러까지 온다.
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(
                                "/api/auth/**",      // 로그인
                                "/ws/**",            // WebSocket 핸드셰이크 (인증은 STOMP CONNECT에서)
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/oauth2/**",        // OAuth 진입
                                "/login/oauth2/**",  // OAuth 콜백
                                "/actuator/health",  // 전체 상태 (health만, 다른 actuator 경로는 인증 필요)
                                "/actuator/health/readiness"   // 컨테이너 헬스체크 (db·diskSpace 확인, Redis는 fail-open이라 그룹에서 제외)
                        ).permitAll()
                        .anyRequest().authenticated() // 나머지는 인증 필요
                )
                // defaultAuthenticationEntryPointFor는 DelegatingAuthenticationEntryPoint에 순서대로 쌓이고,
                // 명시적 default가 없으면 가장 먼저 등록된 것이 fallback이 된다. oauth2Login의 로그인 리다이렉트
                // 엔트리포인트는 이 DSL 실행 이후(http.build())에 등록되므로, 지금은 이 블록의 apiAuthenticationEntryPoint가
                // fallback이다 — 이 블록 위에 defaultAuthenticationEntryPointFor를 추가하면 fallback이 조용히 그쪽으로 넘어간다.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        apiAuthenticationEntryPoint,
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")
                ))
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a
                                .authorizationRequestRepository(cookieAuthorizationRequestRepository))
                        .userInfoEndpoint(u -> u.oidcUserService(customOidcUserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler)
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
