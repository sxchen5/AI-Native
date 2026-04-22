package com.example.doubaoai.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智谱 AI（zai-sdk）开关、模型与密钥；密钥可在配置文件 {@code app.zhipu.api-key} 或环境变量中设置。
 */
@ConfigurationProperties(prefix = "app.zhipu")
public class ZhipuAiProperties {

    /** 为 true 时主对话与短文本走 zai-sdk，不再使用 spring-ai OpenAI 路径 */
    private boolean enabled = false;

    /** 智谱开放平台 API Key（勿提交真实密钥到公开仓库；生产建议用环境变量或 Nacos） */
    private String apiKey = "";

    /** 与 {@link ai.z.openapi.core.Constants} 中模型名一致，如 glm-4-flash */
    private String model = "glm-4-flash";

    /** 默认与智谱开放平台一致；可通过环境变量覆盖 */
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4/";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
