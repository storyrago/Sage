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
                        .requestMatchers(
                                "/api/auth/**",      // 로그인
                                "/ws/**",            // WebSocket 핸드셰이크 (인증은 STOMP CONNECT에서)
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/oauth2/**",        // OAuth 진입
                                "/login/oauth2/**",  // OAuth 콜백
                                "/actuator/health"   // 배포 기동 검증 (health만, 다른 actuator 경로는 인증 필요)
                        ).permitAll()
                        .anyRequest().authenticated() // 나머지는 인증 필요
                )
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
