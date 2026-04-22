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
import com.example.doubaoai.chatservice.util.TextClipUtil;
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

    private static final int MAX_CHARS = 25;

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
                    StoredMessage lastUser = null;
                    StoredMessage lastAssistant = null;
                    for (int i = all.size() - 1; i >= 0; i--) {
                        StoredMessage m = all.get(i);
                        if (lastUser == null && m.role() == ChatRole.USER) {
                            lastUser = m;
                        }
                        if (lastAssistant == null && m.role() == ChatRole.ASSISTANT) {
                            lastAssistant = m;
                        }
                        if (lastUser != null && lastAssistant != null) {
                            break;
                        }
                    }
                    if (lastUser == null && lastAssistant == null) {
                        return ResponseEntity.ok(new SuggestionsResponse(List.of()));
                    }
                    StringBuilder ctx = new StringBuilder();
                    if (lastUser != null) {
                        ctx.append("【用户最近提问】\n")
                                .append(clipForPrompt(lastUser.content()))
                                .append("\n\n");
                    }
                    if (lastAssistant != null) {
                        ctx.append("【助手最近回复】\n")
                                .append(clipForPrompt(lastAssistant.content()));
                    }
                    String system = """
                            你是追问建议助手。上面「用户最近提问」与「助手最近回复」是用户当前话题的全部依据。
                            请基于这两段内容，生成恰好3条用户很可能接着问的问题；每条必须与上文主题直接相关，禁止泛泛的「你好」「谢谢」「介绍一下」等无关句。
                            每条问句要自然、通顺、像真人追问，不要用生硬的关键词堆砌。
                            输出格式：严格3行，每行一条完整问句；不要编号、不要引号、不要任何前后说明；每条不超过25个汉字（或语义等长的英文整句）；句末不要用省略号。
                            """;
                    String raw = aiStreamService.generateShortText(system, ctx.toString()).block(Duration.ofSeconds(45));
                    List<String> qs = parseThreeLines(raw == null ? "" : raw);
                    log.debug("suggestions sessionId={} count={}", req.sessionId(), qs.size());
                    return ResponseEntity.ok(new SuggestionsResponse(qs));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String clipForPrompt(String content) {
        if (content == null) {
            return "";
        }
        String s = content.strip().replace('\r', ' ').replace('\n', ' ');
        if (s.length() > 3500) {
            return s.substring(0, 3500) + "（后文略）";
        }
        return s;
    }

    private static List<String> parseThreeLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.strip().split("\\R")) {
            String s = line.strip();
            s = s.replaceFirst("^\\d+[\\.、)\\]]\\s*", "").replaceAll("^[\"'「]|[\"'」]$", "").strip();
            s = TextClipUtil.stripTrailingEllipsis(s);
            if (!s.isEmpty()) {
                out.add(TextClipUtil.clipNatural(s, MAX_CHARS));
            }
            if (out.size() >= 3) {
                break;
            }
        }
        if (out.isEmpty()) {
            String one = TextClipUtil.stripTrailingEllipsis(raw.strip().replaceFirst("^[\"'「]|[\"'」]$", "").strip());
            if (!one.isEmpty()) {
                out.add(TextClipUtil.clipNatural(one, MAX_CHARS));
            }
        }
        return out;
    }
}
