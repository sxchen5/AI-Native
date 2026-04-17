package com.example.doubaoai.chatservice.config;

import java.util.List;

import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;

/**
 * {@link OpenAiSdkChatModel} 需要一个 {@link ToolCallingManager}。
 * <p>
 * 本示例不启用函数调用，因此提供最小可用的默认实现（空工具列表）。
 */
@Configuration
public class ToolCallingBeansConfiguration {

    @Bean
    public ToolCallingManager toolCallingManager() {
        var resolver = new DelegatingToolCallbackResolver(List.of(new StaticToolCallbackResolver(List.of())));
        var processor = DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(false)
                .build();
        return DefaultToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .toolCallbackResolver(resolver)
                .toolExecutionExceptionProcessor(processor)
                .build();
    }
}
