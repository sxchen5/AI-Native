package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param content 新对话时为必填；带 {@code restartFromUserMessageId} 重新生成时可为空（沿用该条用户消息原文）
 * @param restartFromUserMessageId 若设置：在该用户消息之后截断历史，并以 {@code content}（可空）更新该条用户消息
 */
public record ChatStreamRequest(
        @NotBlank String sessionId,
        @Size(max = 16000) String content,
        String restartFromUserMessageId) {
}
