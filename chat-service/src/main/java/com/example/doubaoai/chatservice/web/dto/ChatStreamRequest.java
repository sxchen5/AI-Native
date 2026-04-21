package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param content 新对话时为必填；带 {@code restartFromUserMessageId} 重新生成时可为空（沿用该条用户消息原文）
 * @param restartFromUserMessageId 若设置：在该用户消息之后截断历史，并以 {@code content}（可空）更新该条用户消息
 * @param appendAfterUserMessageId 若设置：在保留全部历史的前提下，追加一条新的用户消息（内容为 {@code content}），再流式生成助手回复
 */
public record ChatStreamRequest(
        @NotBlank String sessionId,
        /** 含附件解析全文时可较长，服务端仍建议控制模型上下文 */
        @Size(max = 500_000) String content,
        String restartFromUserMessageId,
        String appendAfterUserMessageId) {
}
