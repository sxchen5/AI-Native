package com.example.doubaoai.chatservice.web;

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

    private String mergeFeedback(String existingJson, String vote) throws Exception {
        ObjectNode node;
        if (existingJson == null || existingJson.isBlank()) {
            node = objectMapper.createObjectNode();
        }
        else {
            JsonNode parsed = objectMapper.readTree(existingJson);
            if (parsed instanceof ObjectNode on) {
                node = on;
            }
            else {
                node = objectMapper.createObjectNode();
            }
        }
        if ("clear".equals(vote)) {
            node.remove("feedback");
        }
        else {
            node.put("feedback", vote);
        }
        return objectMapper.writeValueAsString(node);
    }
}
