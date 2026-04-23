package com.example.doubaoai.chatservice.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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
 * 新对话落地页推荐问题：由模型生成 9 条中文短问句；每次请求附带随机种子提示，并提高采样温度，减少重复。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatLandingSuggestionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatLandingSuggestionsController.class);

    private static final int MAX_CHARS = 36;
    private static final int WANT = 9;
    /** 落地页专用：较高温度使每次开场问题更易变化 */
    private static final double LANDING_TEMPERATURE = 0.92;

    private static final List<String> FALLBACK = List.of(
            "什么是 AI Agent？",
            "Java 后端最近有哪些趋势？",
            "用 Spring Boot 写个健康检查接口",
            "如何把 Markdown 转成 PDF？",
            "解释一下 SSE 和 WebSocket 的区别",
            "帮我写一段周报总结模版",
            "中小企业做数字化转型从哪里起步？",
            "跨境电商独立站怎么选支付与物流？",
            "新手学 Python 做数据分析该练哪些项目？",
            "合同里的「不可抗力」一般怎么约定？",
            "高血压患者在饮食上要注意什么？",
            "幼儿园孩子分离焦虑家长怎么应对？");

    private final ChatAiStreamService aiStreamService;

    public ChatLandingSuggestionsController(ChatAiStreamService aiStreamService) {
        this.aiStreamService = aiStreamService;
    }

    @GetMapping("/landing-suggestions")
    public ResponseEntity<LandingSuggestionsResponse> landingSuggestions() {
        int seed = ThreadLocalRandom.current().nextInt(1_000_000, Integer.MAX_VALUE);
        String system = """
                你是中文对话产品的「开场问题」策划：请生成恰好9条**用户可能想向 AI 提问**的中文短句。
                要求：覆盖尽量多元的行业与场景（如科技、制造、金融、医疗、教育、法律、市场、设计、生活、政务等），彼此主题不要雷同；**不要**与常见固定示例句雷同，尽量新颖、具体。
                每条要自然、像真实用户会输入的问句；不要编号、不要引号、不要任何前后说明；每条不超过36个汉字；不要用省略号结尾。
                输出格式：严格9行，每行一条完整问句。
                """;
        String userLine = "请直接输出9行问题。本次创意种子：" + seed + "（仅用于让你换一批角度，不要输出种子数字本身）。";
        String raw = "";
        try {
            raw = aiStreamService.generateShortText(system, userLine, LANDING_TEMPERATURE).block(Duration.ofSeconds(90));
        } catch (Exception e) {
            log.warn("landing-suggestions AI 失败，使用备选: {}", e.toString());
        }
        List<String> parsed = parseLines(raw == null ? "" : raw, WANT);
        List<String> merged = mergeUnique(parsed, shuffleFallback(seed), WANT);
        log.debug("landing-suggestions seed={} count={}", seed, merged.size());
        return ResponseEntity.ok(new LandingSuggestionsResponse(merged));
    }

    /** 备选列表按种子轮转顺序，便于在模型失败时仍有一定变化 */
    private static List<String> shuffleFallback(int seed) {
        int n = FALLBACK.size();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(FALLBACK.get((seed + i) % n));
        }
        return out;
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
