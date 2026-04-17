package com.example.doubaoai.chatservice.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件载荷：前端按 type 分支处理（开始 / 增量 / 结束 / 错误）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SseEventDto(
        String type,
        String assistantMessageId,
        String text,
        String message) {
    public static SseEventDto start(String assistantMessageId) {
        return new SseEventDto("start", assistantMessageId, null, null);
    }

    public static SseEventDto delta(String text) {
        return new SseEventDto("delta", null, text, null);
    }

    public static SseEventDto done() {
        return new SseEventDto("done", null, null, null);
    }

    public static SseEventDto error(String message) {
        return new SseEventDto("error", null, null, message);
    }
}
