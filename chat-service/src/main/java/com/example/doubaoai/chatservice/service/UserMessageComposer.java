package com.example.doubaoai.chatservice.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.example.doubaoai.chatservice.domain.StoredMessage;
import com.example.doubaoai.chatservice.web.dto.ModelContextDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 将用户展示文本与 {@link ModelContextDto} 合并为发给模型的单条 {@link UserMessage} 文本。
 */
@Component
public class UserMessageComposer {

    private static final String META_MODEL_USER_TEXT = "modelUserText";

    private final ObjectMapper objectMapper;

    public UserMessageComposer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public UserMessage toModelUserMessage(String displayText, String modelContextJson) {
        String merged = mergeForModel(displayText, modelContextJson);
        return new UserMessage(merged);
    }

    /** 历史中的用户消息：若 metadata 中存有合并后的 modelUserText 则用之，否则用 content。 */
    public String textForHistoryUser(StoredMessage m) {
        String fromMeta = readModelUserTextFromMetadata(m.metadata());
        if (fromMeta != null && !fromMeta.isBlank()) {
            return fromMeta;
        }
        return m.content() == null ? "" : m.content();
    }

    /**
     * 当用户未输入文字时，从 modelContext 生成气泡展示用短文本（如首个附件名或语音摘要）。
     */
    public String fallbackDisplayFromContext(String modelContextJson) {
        ModelContextDto ctx = parse(modelContextJson);
        if (ctx == null) {
            return "";
        }
        if (ctx.userBubble() != null && ctx.userBubble().chips() != null) {
            for (ModelContextDto.UserChipDto c : ctx.userBubble().chips()) {
                if (c != null && c.label() != null && !c.label().isBlank()) {
                    return c.label().strip();
                }
            }
        }
        if (ctx.attachments() != null) {
            for (ModelContextDto.AttachmentPartDto a : ctx.attachments()) {
                if (a != null && a.fileName() != null && !a.fileName().isBlank()) {
                    return a.fileName().strip();
                }
            }
        }
        if (ctx.voiceTranscript() != null && !ctx.voiceTranscript().isBlank()) {
            String v = ctx.voiceTranscript().strip();
            return v.length() > 48 ? v.substring(0, 45) + "…" : v;
        }
        return "";
    }

    public String mergeForModel(String displayText, String modelContextJson) {
        String display = displayText == null ? "" : displayText.strip();
        ModelContextDto ctx = parse(modelContextJson);
        if (ctx == null && display.isEmpty()) {
            return "";
        }
        if (ctx == null) {
            return display;
        }
        List<String> parts = new ArrayList<>();
        if (!display.isEmpty()) {
            parts.add(display);
        }
        if (ctx.voiceTranscript() != null && !ctx.voiceTranscript().isBlank()) {
            parts.add("【语音转写】\n" + ctx.voiceTranscript().strip());
        }
        if (ctx.attachments() != null) {
            for (ModelContextDto.AttachmentPartDto a : ctx.attachments()) {
                if (a == null) continue;
                String name = a.fileName() == null ? "附件" : a.fileName();
                String body = a.extractedText() == null ? "" : a.extractedText().strip();
                if (body.isEmpty()) continue;
                parts.add("【附件：" + name + "】\n" + body);
            }
        }
        return String.join("\n\n", parts);
    }

    /** 合并前端 modelContext 与 modelUserText，写入用户消息 metadata。 */
    public String buildUserMessageMetadata(String modelContextJson, String modelUserText) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            if (modelContextJson != null && !modelContextJson.isBlank()) {
                JsonNode ctx = objectMapper.readTree(modelContextJson);
                if (ctx.isObject()) {
                    try {
                        node.setAll((com.fasterxml.jackson.databind.node.ObjectNode) ctx);
                    }
                    catch (Exception ignored) {
                        // ignore invalid merge
                    }
                }
            }
            node.put(META_MODEL_USER_TEXT, modelUserText);
            return objectMapper.writeValueAsString(node);
        }
        catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(objectMapper.createObjectNode().put(META_MODEL_USER_TEXT, modelUserText));
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private String readModelUserTextFromMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = objectMapper.readTree(metadataJson);
            JsonNode v = n.get(META_MODEL_USER_TEXT);
            return v == null || v.isNull() ? null : v.asText();
        }
        catch (Exception e) {
            return null;
        }
    }

    private ModelContextDto parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ModelContextDto.class);
        }
        catch (Exception e) {
            return null;
        }
    }
}
