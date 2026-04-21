package com.example.doubaoai.chatservice.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.doubaoai.chatservice.domain.ChatSession;
import com.example.doubaoai.chatservice.store.InMemoryChatStore;
import com.example.doubaoai.chatservice.web.dto.CreateSessionRequest;
import com.example.doubaoai.chatservice.web.dto.MessageDto;
import com.example.doubaoai.chatservice.web.dto.RenameSessionRequest;
import com.example.doubaoai.chatservice.web.dto.SessionSummaryDto;

import jakarta.validation.Valid;

/**
 * 会话管理 API：列表 / 创建 / 重命名 / 删除 / 拉取消息。
 */
@RestController
@RequestMapping("/api/sessions")
@Validated
public class SessionController {

    private final InMemoryChatStore store;

    public SessionController(InMemoryChatStore store) {
        this.store = store;
    }

    @GetMapping
    public List<SessionSummaryDto> list() {
        return store.listSessions().stream()
                .map(s -> new SessionSummaryDto(s.id(), s.title(), s.createdAt(), s.updatedAt()))
                .toList();
    }

    @PostMapping
    public SessionSummaryDto create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null ? null : request.title();
        ChatSession session = store.createSession(title);
        return new SessionSummaryDto(session.id(), session.title(), session.createdAt(), session.updatedAt());
    }

    @PatchMapping("/{sessionId}")
    public ResponseEntity<Void> rename(@PathVariable String sessionId, @Valid @RequestBody RenameSessionRequest request) {
        try {
            store.rename(sessionId, request.title());
            return ResponseEntity.noContent().build();
        }
        catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        store.delete(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<MessageDto>> messages(@PathVariable String sessionId) {
        return store.find(sessionId)
                .map(s -> s.messagesView().stream()
                        .map(m -> new MessageDto(m.id(), m.role(), m.content(), m.createdAt(), m.metadata()))
                        .toList())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
