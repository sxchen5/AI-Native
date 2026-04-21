package com.example.doubaoai.chatservice.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话标题、文档标题等短文本摘要（调用模型一次，阻塞等待结果）。
 */
@Service
public class ChatTitleAiService {

    private static final Logger log = LoggerFactory.getLogger(ChatTitleAiService.class);

    private static final Duration BLOCK = Duration.ofSeconds(45);

    private final ChatAiStreamService chatAiStreamService;

    public ChatTitleAiService(ChatAiStreamService chatAiStreamService) {
        this.chatAiStreamService = chatAiStreamService;
    }

    public String summarizeConversationTitle(String userText, String assistantText) {
        String u = truncate(strip(userText), 1200);
        String a = truncate(strip(assistantText), 2000);
        if (u.isEmpty() && a.isEmpty()) {
            return "";
        }
        String sys = """
                你是对话标题生成器。根据首条「用户消息」与对应「助手回复」提炼一句新会话标题。
                规则：仅输出一行；总长度不超过15个汉字（或等宽英文单词），不要引号、书名号、冒号结尾；不要「对话」「聊天」等空话；中文优先。
                """;
        String user = "【用户】\n" + (u.isEmpty() ? "（无文字，可能仅有附件）" : u) + "\n\n【助手】\n" + (a.isEmpty() ? "（空）" : a);
        String raw = chatAiStreamService.generateShortText(sys, user).block(BLOCK);
        if (raw == null) {
            raw = "";
        }
        String t = sanitizeTitle(raw, 15);
        log.debug("conversation title generated len={}", t.length());
        return t;
    }

    public String summarizeDocumentTitle(String markdownBody) {
        String excerpt = truncate(strip(markdownBody), 3000);
        if (excerpt.isEmpty()) {
            return "文档";
        }
        String sys = """
                你是文档命名助手。根据正文内容生成简短文档标题。
                规则：仅输出一行；8～24 字；概括主题；不要引号、书名号；不要「文档」「Markdown」等套话；中文优先。
                """;
        String user = "正文节选：\n" + excerpt;
        String raw = chatAiStreamService.generateShortText(sys, user).block(BLOCK);
        if (raw == null) {
            raw = "";
        }
        String t = sanitizeTitle(raw, 48);
        String out = t.isEmpty() ? "文档" : t;
        log.debug("document title generated len={}", out.length());
        return out;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    static String sanitizeTitle(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String t = raw.strip().replace('\r', ' ').replace('\n', ' ');
        t = t.replaceFirst("^[「\"'“]+", "").replaceFirst("[」\"'”]+$", "").strip();
        if (t.length() > maxLen) {
            t = t.substring(0, maxLen - 1) + "…";
        }
        return t;
    }
}
