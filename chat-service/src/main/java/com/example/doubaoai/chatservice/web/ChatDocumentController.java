package com.example.doubaoai.chatservice.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.doubaoai.chatservice.domain.ChatRole;
import com.example.doubaoai.chatservice.domain.StoredMessage;
import com.example.doubaoai.chatservice.service.ChatTitleAiService;
import com.example.doubaoai.chatservice.store.InMemoryChatStore;
import com.example.doubaoai.chatservice.web.dto.ConvertDocumentRequest;
import com.example.doubaoai.chatservice.web.dto.FreezeDocumentRequest;
import com.example.doubaoai.chatservice.web.dto.MessageDto;
import com.example.doubaoai.chatservice.web.dto.UpdateDocumentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

/**
 * 将助手消息转为可持久化的「文档卡片」消息（追加新消息，不修改原助手回复）。
 */
@RestController
@RequestMapping("/api/chat/document")
@Validated
public class ChatDocumentController {

    private final InMemoryChatStore store;
    private final ObjectMapper objectMapper;
    private final ChatTitleAiService titleAiService;

    public ChatDocumentController(InMemoryChatStore store, ObjectMapper objectMapper, ChatTitleAiService titleAiService) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.titleAiService = titleAiService;
    }

    @PostMapping("/convert")
    public ResponseEntity<MessageDto> convert(@Valid @RequestBody ConvertDocumentRequest req) {
        try {
            return store.find(req.sessionId())
                    .map(session -> {
                        StoredMessage src = session.historySnapshot().stream()
                                .filter(m -> m.id().equals(req.sourceAssistantMessageId()))
                                .findFirst()
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "源消息不存在"));
                        if (src.role() != ChatRole.ASSISTANT) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能转换助手消息");
                        }
                        String body = src.content() == null ? "" : src.content();
                        for (StoredMessage m : session.historySnapshot()) {
                            if (m.role() != ChatRole.ASSISTANT || m.metadata() == null || m.metadata().isBlank()) {
                                continue;
                            }
                            Map<String, Object> ex = readMeta(m.metadata());
                            if ("document_card".equals(ex.get("type")) && Objects.equals(src.id(), ex.get("sourceAssistantId"))) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT, "已为该回复生成文档卡片");
                            }
                        }
                        String title = titleAiService.summarizeDocumentTitle(body);
                        if (title.isBlank()) {
                            title = firstLineTitle(body);
                        }
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("type", "document_card");
                        meta.put("title", title);
                        meta.put("markdownBody", body);
                        meta.put("sourceAssistantId", src.id());
                        meta.put("frozen", Boolean.FALSE);
                        String metaJson = writeMeta(meta);
                        StoredMessage doc = store.appendAssistantMessage(req.sessionId(), body, metaJson);
                        return ResponseEntity.ok(toDto(doc));
                    })
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        }
        catch (ResponseStatusException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/update")
    public ResponseEntity<MessageDto> update(@Valid @RequestBody UpdateDocumentRequest req) {
        try {
            return store.find(req.sessionId())
                    .map(session -> {
                        StoredMessage old = session.historySnapshot().stream()
                                .filter(m -> m.id().equals(req.messageId()))
                                .findFirst()
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
                        if (old.role() != ChatRole.ASSISTANT) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能更新助手消息");
                        }
                        Map<String, Object> meta = readMeta(old.metadata());
                        if (!"document_card".equals(meta.get("type"))) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不是文档消息");
                        }
                        meta.put("markdownBody", req.markdownBody());
                        String aiTitle = titleAiService.summarizeDocumentTitle(req.markdownBody());
                        meta.put("title", aiTitle.isBlank() ? firstLineTitle(req.markdownBody()) : aiTitle);
                        String metaJson = writeMeta(meta);
                        store.updateMessageContentAndMetadata(req.sessionId(), req.messageId(), req.markdownBody(), metaJson);
                        StoredMessage updated = session.historySnapshot().stream()
                                .filter(m -> m.id().equals(req.messageId()))
                                .findFirst()
                                .orElseThrow();
                        return ResponseEntity.ok(toDto(updated));
                    })
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        }
        catch (ResponseStatusException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/freeze")
    public ResponseEntity<MessageDto> freeze(@Valid @RequestBody FreezeDocumentRequest req) {
        return store.find(req.sessionId())
                .map(session -> {
                    StoredMessage target = session.historySnapshot().stream()
                            .filter(m -> m.id().equals(req.messageId()))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
                    if (target.role() != ChatRole.ASSISTANT) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能冻结助手消息");
                    }
                    Map<String, Object> meta = readMeta(target.metadata());
                    if (!"document_card".equals(meta.get("type"))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不是文档消息");
                    }
                    meta.put("frozen", Boolean.TRUE);
                    store.updateMessageMetadata(req.sessionId(), target.id(), writeMeta(meta));
                    StoredMessage updated = session.historySnapshot().stream()
                            .filter(m -> m.id().equals(target.id()))
                            .findFirst()
                            .orElseThrow();
                    return ResponseEntity.ok(toDto(updated));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private static MessageDto toDto(StoredMessage m) {
        return new MessageDto(m.id(), m.role(), m.content(), m.createdAt(), m.metadata());
    }

    private static String firstLineTitle(String md) {
        if (md == null) {
            return "文档";
        }
        String s = md.strip().replace('\r', ' ');
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).strip();
        }
        if (s.startsWith("#")) {
            s = s.replaceFirst("^#+\\s*", "").strip();
        }
        if (s.length() > 48) {
            return s.substring(0, 45) + "…";
        }
        return s.isEmpty() ? "文档" : s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        }
        catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeMeta(Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
