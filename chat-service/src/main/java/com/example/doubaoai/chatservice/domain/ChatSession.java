package com.example.doubaoai.chatservice.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话聚合：标题 + 消息列表（线程安全由上层 Store 保证）。
 */
public class ChatSession {

    private final String id;
    private volatile String title;
    private volatile Instant createdAt;
    private volatile Instant updatedAt;
    private final List<StoredMessage> messages = new ArrayList<>();

    public ChatSession(String id, String title, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        touch();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<StoredMessage> messagesView() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    public void addMessage(StoredMessage message) {
        synchronized (messages) {
            messages.add(message);
        }
        touch();
    }

    public void replaceMessage(String messageId, StoredMessage replacement) {
        synchronized (messages) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).id().equals(messageId)) {
                    messages.set(i, replacement);
                    touch();
                    return;
                }
            }
        }
        throw new IllegalArgumentException("消息不存在: " + messageId);
    }

    public List<StoredMessage> historySnapshot() {
        synchronized (messages) {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
