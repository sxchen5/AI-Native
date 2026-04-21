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
        return appendUserMessage(sessionId, content, null);
    }

    public StoredMessage appendUserMessage(String sessionId, String content, String metadata) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        String messageId = UUID.randomUUID().toString().replace("-", "");
        StoredMessage message = new StoredMessage(messageId, ChatRole.USER, content, Instant.now(), metadata);
        session.addMessage(message);
        return message;
    }

    /** 追加一条已带正文的助手消息（如文档卡片）。 */
    public StoredMessage appendAssistantMessage(String sessionId, String content, String metadata) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        String messageId = UUID.randomUUID().toString().replace("-", "");
        StoredMessage message = new StoredMessage(messageId, ChatRole.ASSISTANT, content, Instant.now(), metadata);
        session.addMessage(message);
        return message;
    }

    public StoredMessage appendAssistantPlaceholder(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        String messageId = UUID.randomUUID().toString().replace("-", "");
        StoredMessage message = new StoredMessage(messageId, ChatRole.ASSISTANT, "", Instant.now(), null);
        session.addMessage(message);
        return message;
    }

    /**
     * 将指定用户消息替换为新文本，并删除其后的所有消息（编辑后重发）。
     */
    public void replaceUserMessageAndTruncateAfter(String sessionId, String userMessageId, String newContent) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        List<StoredMessage> snapshot = session.historySnapshot();
        StoredMessage old = snapshot.stream()
                .filter(m -> m.id().equals(userMessageId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        if (old.role() != ChatRole.USER) {
            throw new IllegalArgumentException("只能替换用户消息");
        }
        if (newContent == null || newContent.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        session.replaceMessage(userMessageId,
                new StoredMessage(old.id(), ChatRole.USER, newContent.strip(), old.createdAt(), old.metadata()));
        session.removeMessagesAfter(userMessageId);
    }

    /**
     * 删除指定消息之后的所有消息（重新生成时保留该条用户消息）。
     */
    public void truncateAfterMessage(String sessionId, String messageId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        session.removeMessagesAfter(messageId);
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
                new StoredMessage(old.id(), old.role(), fullContent, old.createdAt(), old.metadata()));
    }

    public void updateMessageContentAndMetadata(String sessionId, String messageId, String content, String metadata) {
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
                new StoredMessage(old.id(), old.role(), content, old.createdAt(), metadata));
    }

    public void updateMessageMetadata(String sessionId, String messageId, String metadata) {
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
                new StoredMessage(old.id(), old.role(), old.content(), old.createdAt(), metadata));
    }
}
