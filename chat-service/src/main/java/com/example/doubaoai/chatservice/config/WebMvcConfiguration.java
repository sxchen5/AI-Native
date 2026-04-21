package com.example.doubaoai.chatservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 直连 chat-service（绕过网关）时的跨域支持；生产环境建议仅由网关对外暴露。
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:4173",
                        "http://127.0.0.1:4173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Cache-Control", "Content-Type", "X-Captcha-Id")
                .allowCredentials(true);
    }
}
