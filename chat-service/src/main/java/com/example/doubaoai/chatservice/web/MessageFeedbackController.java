package com.example.doubaoai.chatservice.web;

import java.util.Iterator;
import java.util.Map;

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
import com.example.doubaoai.chatservice.store.InMemoryChatStore;
import com.example.doubaoai.chatservice.web.dto.MessageDto;
import com.example.doubaoai.chatservice.web.dto.MessageFeedbackRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/chat/message")
@Validated
public class MessageFeedbackController {

    private final InMemoryChatStore store;
    private final ObjectMapper objectMapper;

    public MessageFeedbackController(InMemoryChatStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/feedback")
    public ResponseEntity<MessageDto> feedback(@Valid @RequestBody MessageFeedbackRequest req) {
        String v = req.vote() == null ? "" : req.vote().strip().toLowerCase();
        if (!v.equals("up") && !v.equals("down") && !v.equals("clear")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vote 须为 up、down 或 clear");
        }
        return store.find(req.sessionId())
                .map(session -> {
                    StoredMessage old = session.historySnapshot().stream()
                            .filter(m -> m.id().equals(req.messageId()))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
                    if (old.role() != ChatRole.ASSISTANT) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持助手消息");
                    }
                    String metaJson = mergeFeedback(old.metadata(), v);
                    store.updateMessageMetadata(req.sessionId(), req.messageId(), metaJson);
                    StoredMessage updated = session.historySnapshot().stream()
                            .filter(m -> m.id().equals(req.messageId()))
                            .findFirst()
                            .orElseThrow();
                    return ResponseEntity.ok(new MessageDto(updated.id(), updated.role(), updated.content(), updated.createdAt(), updated.metadata()));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    /**
     * 将 feedback 写入可变的 JSON 对象；原 metadata 非对象或非法 JSON 时从空对象开始，避免 Jackson 不可变节点或解析异常。
     */
    private String mergeFeedback(String existingJson, String vote) {
        ObjectNode node = objectMapper.createObjectNode();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(existingJson);
                if (root.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        node.set(e.getKey(), e.getValue().deepCopy());
                    }
                }
            }
            catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息元数据格式无效", e);
            }
        }
        if ("clear".equals(vote)) {
            node.remove("feedback");
        }
        else {
            node.put("feedback", vote);
        }
        try {
            return objectMapper.writeValueAsString(node);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
