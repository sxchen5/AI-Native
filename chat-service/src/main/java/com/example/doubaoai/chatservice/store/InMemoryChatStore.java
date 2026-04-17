package com.example.doubaoai.chatservice.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.example.doubaoai.chatservice.domain.ChatRole;
import com.example.doubaoai.chatservice.domain.ChatSession;
import com.example.doubaoai.chatservice.domain.StoredMessage;

/**
 * 内存版会话与消息存储（进程内 ConcurrentHashMap）。
 */
@Component
public class InMemoryChatStore {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatSession createSession(String title) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String safeTitle = (title == null || title.isBlank()) ? "新对话" : title.strip();
        ChatSession session = new ChatSession(id, safeTitle, now);
        sessions.put(id, session);
        return session;
    }

    public List<ChatSession> listSessions() {
        List<ChatSession> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparing(ChatSession::updatedAt).reversed());
        return list;
    }

    public Optional<ChatSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void rename(String sessionId, String newTitle) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        session.setTitle(newTitle.strip());
    }

    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    public StoredMessage appendUserMessage(String sessionId, String content) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        String messageId = UUID.randomUUID().toString().replace("-", "");
        StoredMessage message = new StoredMessage(messageId, ChatRole.USER, content, Instant.now());
        session.addMessage(message);
        return message;
    }

    public StoredMessage appendAssistantPlaceholder(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        String messageId = UUID.randomUUID().toString().replace("-", "");
        StoredMessage message = new StoredMessage(messageId, ChatRole.ASSISTANT, "", Instant.now());
        session.addMessage(message);
        return message;
    }

    public void updateAssistantContent(String sessionId, String messageId, String fullContent) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        List<StoredMessage> snapshot = session.historySnapshot();
        StoredMessage old = snapshot.stream()
                .filter(m -> m.id().equals(messageId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        session.replaceMessage(messageId,
                new StoredMessage(old.id(), old.role(), fullContent, old.createdAt()));
    }
}
