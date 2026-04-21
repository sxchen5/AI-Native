package com.example.doubaoai.chatservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openaisdk.autoconfigure.OpenAiSdkAutoConfigurationUtil;
import org.springframework.ai.model.openaisdk.autoconfigure.OpenAiSdkChatProperties;
import org.springframework.ai.model.openaisdk.autoconfigure.OpenAiSdkConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openaisdk.OpenAiSdkChatModel;
import org.springframework.ai.openaisdk.setup.OpenAiSdkSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;

/**
 * Spring AI {@code 2.0.0-M4} 的 starter 会固定依赖 Spring Boot {@code 4.1.x}，与项目要求的 Boot {@code 3.3.x} 不兼容。
 * <p>
 * 因此这里改为“显式装配”路径：引入 {@code spring-ai-openai-sdk} 核心模块，并复用官方自动配置里的客户端构建逻辑
 * （{@link OpenAiSdkSetup} + {@link OpenAiSdkAutoConfigurationUtil}）。
 */
@Configuration
public class OpenAiManualConfiguration {

    /**
     * 显式注册配置属性 Bean：项目排除了 {@code OpenAiSdkChatAutoConfiguration} 后，仅依赖
     * {@code @EnableConfigurationProperties} 在部分环境下会在条件解析阶段触发绑定异常。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "spring.ai.openai-sdk")
    public OpenAiSdkConnectionProperties openAiSdkConnectionProperties() {
        return new OpenAiSdkConnectionProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "spring.ai.openai-sdk.chat")
    public OpenAiSdkChatProperties openAiSdkChatProperties() {
        return new OpenAiSdkChatProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAiSdkChatModel openAiSdkChatModel(OpenAiSdkConnectionProperties connectionProperties,
            OpenAiSdkChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate> eligibilityProvider) {
        var resolved = OpenAiSdkAutoConfigurationUtil.resolveConnectionProperties(connectionProperties, chatProperties);

        var sync = OpenAiSdkSetup.setupSyncClient(
                resolved.getBaseUrl(),
                resolved.getApiKey(),
                resolved.getCredential(),
                resolved.getMicrosoftDeploymentName(),
                resolved.getMicrosoftFoundryServiceVersion(),
                resolved.getOrganizationId(),
                resolved.isMicrosoftFoundry(),
                resolved.isGitHubModels(),
                resolved.getModel(),
                resolved.getTimeout(),
                resolved.getMaxRetries(),
                resolved.getProxy(),
                resolved.getCustomHeaders());

        var async = OpenAiSdkSetup.setupAsyncClient(
                resolved.getBaseUrl(),
                resolved.getApiKey(),
                resolved.getCredential(),
                resolved.getMicrosoftDeploymentName(),
                resolved.getMicrosoftFoundryServiceVersion(),
                resolved.getOrganizationId(),
                resolved.isMicrosoftFoundry(),
                resolved.isGitHubModels(),
                resolved.getModel(),
                resolved.getTimeout(),
                resolved.getMaxRetries(),
                resolved.getProxy(),
                resolved.getCustomHeaders());

        return OpenAiSdkChatModel.builder()
                .openAiClient(sync)
                .openAiClientAsync(async)
                .options(chatProperties.getOptions())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP))
                .toolExecutionEligibilityPredicate(eligibilityProvider.getIfUnique(() -> null))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(OpenAiSdkChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个专业、友好、简洁的中文助手。
                        回答尽量结构化，避免无意义寒暄；涉及不确定信息时要明确说明不确定性。
                        """)
                .build();
    }
}
