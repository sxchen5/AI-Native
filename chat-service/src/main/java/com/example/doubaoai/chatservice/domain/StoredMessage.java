package com.example.doubaoai.chatservice.domain;

import java.time.Instant;

/**
 * 单条会话消息（内存持久化）。
 */
public record StoredMessage(
        String id,
        ChatRole role,
        String content,
        Instant createdAt) {
}
