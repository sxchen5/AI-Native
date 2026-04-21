package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param content 新对话时为必填；带 {@code restartFromUserMessageId} 重新生成时可为空（沿用该条用户消息原文）
 * @param restartFromUserMessageId 若设置：在该用户消息之后截断历史，并以 {@code content}（可空）更新该条用户消息
 * @param appendAfterUserMessageId 若设置：在保留全部历史的前提下，追加一条新的用户消息（内容为 {@code content}），再流式生成助手回复
 * @param modelContextJson 可选 JSON（{@link com.example.doubaoai.chatservice.web.dto.ModelContextDto}），语音/附件解析等供模型使用，用户气泡仍用 content 展示
 */
public record ChatStreamRequest(
        @NotBlank String sessionId,
        /** 用户气泡展示文本；可与 modelContext 组合（可空，仅附件/语音时由上下文补全） */
        @NotNull @Size(max = 16_000) String content,
        String restartFromUserMessageId,
        String appendAfterUserMessageId,
        @Size(max = 500_000) String modelContextJson) {
}
