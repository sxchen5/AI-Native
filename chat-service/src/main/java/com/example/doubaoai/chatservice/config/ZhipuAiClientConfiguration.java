package com.example.doubaoai.chatservice.config;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import ai.z.openapi.ZhipuAiClient;

/**
 * 智谱官方 Java SDK：{@code zai-sdk}。
 */
@Configuration
@EnableConfigurationProperties(ZhipuAiProperties.class)
public class ZhipuAiClientConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.zhipu", name = "enabled", havingValue = "true")
    public ZhipuAiClient zhipuAiClient(ZhipuAiProperties props) {
        String key = props.getApiKey();
        if (!StringUtils.hasText(key)) {
            key = System.getenv("ZHIPU_API_KEY");
        }
        if (!StringUtils.hasText(key)) {
            key = System.getenv("OPENAI_API_KEY");
        }
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException(
                    "app.zhipu.enabled=true 但未配置 app.zhipu.api-key（或环境变量 ZHIPU_API_KEY / OPENAI_API_KEY）");
        }
        String base = StringUtils.hasText(props.getBaseUrl()) ? props.getBaseUrl().trim() : "https://open.bigmodel.cn/api/paas/v4/";
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return ZhipuAiClient.builder()
                .apiKey(key)
                .baseUrl(base)
                .networkConfig(0, 60, 0, 60, TimeUnit.MINUTES)
                .build();
    }
}
