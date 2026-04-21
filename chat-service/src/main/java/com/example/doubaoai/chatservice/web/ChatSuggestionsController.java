package com.example.doubaoai.chatservice.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.doubaoai.chatservice.domain.ChatRole;
import com.example.doubaoai.chatservice.domain.StoredMessage;
import com.example.doubaoai.chatservice.service.ChatAiStreamService;
import com.example.doubaoai.chatservice.store.InMemoryChatStore;
import com.example.doubaoai.chatservice.web.dto.SuggestionsRequest;
import com.example.doubaoai.chatservice.web.dto.SuggestionsResponse;

import jakarta.validation.Valid;

/**
 * 根据最近对话生成三条「猜你想问」。
 */
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatSuggestionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatSuggestionsController.class);

    private final InMemoryChatStore store;
    private final ChatAiStreamService aiStreamService;

    public ChatSuggestionsController(InMemoryChatStore store, ChatAiStreamService aiStreamService) {
        this.store = store;
        this.aiStreamService = aiStreamService;
    }

    @PostMapping("/suggestions")
    public ResponseEntity<SuggestionsResponse> suggestions(@Valid @RequestBody SuggestionsRequest req) {
        return store.find(req.sessionId())
                .map(session -> {
                    List<StoredMessage> all = session.historySnapshot();
                    if (all.isEmpty()) {
                        return ResponseEntity.ok(new SuggestionsResponse(List.of()));
                    }
                    int from = Math.max(0, all.size() - 8);
                    StringBuilder sb = new StringBuilder();
                    for (int i = from; i < all.size(); i++) {
                        StoredMessage m = all.get(i);
                        String role = m.role() == ChatRole.USER ? "用户" : "助手";
                        sb.append(role).append("：")
                                .append(m.content() == null ? "" : m.content().strip().replace('\n', ' '))
                                .append("\n");
                    }
                    String system = """
                            你是对话助手。根据下列最近对话（尤其最后一条用户与助手），生成恰好3条用户可能继续追问的问题。
                            要求：每行一条；不要编号、不要引号、不要多余说明；每条不超过15个汉字（或等宽英文词）；中文优先。
                            """;
                    String user = "最近对话：\n" + sb;
                    String raw = aiStreamService.generateShortText(system, user).block(Duration.ofSeconds(45));
                    List<String> qs = parseThreeLines(raw == null ? "" : raw);
                    log.debug("suggestions sessionId={} count={}", req.sessionId(), qs.size());
                    return ResponseEntity.ok(new SuggestionsResponse(qs));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static List<String> parseThreeLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.strip().split("\\R")) {
            String s = line.strip();
            s = s.replaceFirst("^\\d+[\\.、)\\]]\\s*", "").replaceAll("^[\"'「]|[\"'」]$", "").strip();
            if (!s.isEmpty()) {
                out.add(s.length() > 15 ? s.substring(0, 14) + "…" : s);
            }
            if (out.size() >= 3) {
                break;
            }
        }
        if (out.isEmpty()) {
            String one = raw.strip().replaceFirst("^[\"'「]|[\"'」]$", "").strip();
            if (!one.isEmpty()) {
                out.add(one.length() > 15 ? one.substring(0, 14) + "…" : one);
            }
        }
        return out;
    }
}
