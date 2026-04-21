package com.example.doubaoai.chatservice.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 基于 Session 的登录保护：除登录、验证码、健康检查外，其余 /api/** 需认证。
 * <p>
 * 演示环境关闭 CSRF，避免 SSE POST 与纯 JSON 前端集成复杂度（生产应改为 Token + CSRF 或网关统一鉴权）。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthUserProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/captcha", "/api/auth/login", "/api/auth/logout", "/api/auth/me", "/actuator/**")
                                .permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonUnauthorizedEntryPoint(objectMapper))
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    java.util.Map.of("message", accessDeniedException.getMessage())));
                        }))
                .logout(logout -> logout.disable());
        return http.build();
    }
}
