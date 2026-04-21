package com.example.doubaoai.chatservice.domain;

import java.time.Instant;

/**
 * 单条会话消息（内存持久化）。
 *
 * @param metadata 可选 JSON 扩展（如文档卡片、冻结标记等），可为 null
 */
public record StoredMessage(
        String id,
        ChatRole role,
        String content,
        Instant createdAt,
        String metadata) {

    public StoredMessage(String id, ChatRole role, String content, Instant createdAt) {
        this(id, role, content, createdAt, null);
    }
}
