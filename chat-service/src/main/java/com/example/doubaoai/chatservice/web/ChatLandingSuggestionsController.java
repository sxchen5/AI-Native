package com.example.doubaoai.chatservice.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.doubaoai.chatservice.service.ChatAiStreamService;
import com.example.doubaoai.chatservice.util.TextClipUtil;
import com.example.doubaoai.chatservice.web.dto.LandingSuggestionsResponse;

/**
 * 新对话落地页推荐问题：由模型生成中文短问句；失败时返回固定备选列表。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatLandingSuggestionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatLandingSuggestionsController.class);

    private static final int MAX_CHARS = 36;
    private static final int WANT = 6;

    private static final List<String> FALLBACK = List.of(
            "什么是 AI Agent？",
            "Java 后端最近有哪些趋势？",
            "用 Spring Boot 写个健康检查接口",
            "如何把 Markdown 转成 PDF？",
            "解释一下 SSE 和 WebSocket 的区别",
            "帮我写一段周报总结模版");

    private final ChatAiStreamService aiStreamService;

    public ChatLandingSuggestionsController(ChatAiStreamService aiStreamService) {
        this.aiStreamService = aiStreamService;
    }

    @GetMapping("/landing-suggestions")
    public ResponseEntity<LandingSuggestionsResponse> landingSuggestions() {
        String system = """
                你是中文对话产品的「开场问题」策划：请生成恰好6条**用户可能想向 AI 提问**的中文短句。
                要求：覆盖尽量多元的行业与场景（如科技、制造、金融、医疗、教育、法律、市场、设计、生活、政务等），彼此主题不要雷同。
                每条要自然、像真实用户会输入的问句；不要编号、不要引号、不要任何前后说明；每条不超过36个汉字；不要用省略号结尾。
                输出格式：严格6行，每行一条完整问句。
                """;
        String raw = "";
        try {
            raw = aiStreamService.generateShortText(system, "请直接输出6行问题。").block(Duration.ofSeconds(60));
        } catch (Exception e) {
            log.warn("landing-suggestions AI 失败，使用备选: {}", e.toString());
        }
        List<String> parsed = parseLines(raw == null ? "" : raw, WANT);
        List<String> merged = mergeUnique(parsed, FALLBACK, WANT);
        log.debug("landing-suggestions count={}", merged.size());
        return ResponseEntity.ok(new LandingSuggestionsResponse(merged));
    }

    private static List<String> parseLines(String raw, int maxLines) {
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
            if (out.size() >= maxLines) {
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

    private static List<String> mergeUnique(List<String> primary, List<String> fallback, int want) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String s : primary) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String k = s.strip();
            if (k.isEmpty() || seen.contains(k)) {
                continue;
            }
            seen.add(k);
            out.add(k);
            if (out.size() >= want) {
                return out;
            }
        }
        for (String s : fallback) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String k = s.strip();
            if (k.isEmpty() || seen.contains(k)) {
                continue;
            }
            seen.add(k);
            out.add(k);
            if (out.size() >= want) {
                break;
            }
        }
        return out;
    }
}
