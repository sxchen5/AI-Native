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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.doubaoai.chatservice.domain.ChatSession;
import com.example.doubaoai.chatservice.store.InMemoryChatStore;
import com.example.doubaoai.chatservice.web.dto.CreateSessionRequest;
import com.example.doubaoai.chatservice.web.dto.MessageDto;
import com.example.doubaoai.chatservice.web.dto.RenameSessionRequest;
import com.example.doubaoai.chatservice.web.dto.SessionListResponse;
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

    /**
     * 分页列表：按更新时间倒序；offset 0 为最新。limit 最大 30。
     */
    @GetMapping
    public SessionListResponse list(
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        int lim = Math.min(30, Math.max(1, limit));
        int off = Math.max(0, offset);
        List<ChatSession> all = store.listSessions();
        if (off >= all.size()) {
            return new SessionListResponse(List.of(), false);
        }
        int end = Math.min(off + lim, all.size());
        List<SessionSummaryDto> slice = all.subList(off, end).stream()
                .map(s -> new SessionSummaryDto(s.id(), s.title(), s.createdAt(), s.updatedAt()))
                .toList();
        boolean hasMore = end < all.size();
        return new SessionListResponse(slice, hasMore);
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
