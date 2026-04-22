package com.example.doubaoai.chatservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 将 zai-sdk 流式 {@code ModelData} 块解析为增量文本（避免依赖 SDK 内部 choice 类型签名）。
 */
public final class ZhipuStreamChunkParser {

    private ZhipuStreamChunkParser() {
    }

    public static String deltaText(Object chunk, ObjectMapper mapper) {
        if (chunk == null || mapper == null) {
            return "";
        }
        try {
            JsonNode n = mapper.valueToTree(chunk);
            JsonNode choices = n.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return "";
            }
            JsonNode first = choices.get(0);
            if (first == null) {
                return "";
            }
            JsonNode delta = first.get("delta");
            if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                return delta.get("content").asText("");
            }
            return "";
        }
        catch (Exception e) {
            return "";
        }
    }

    public static String syncAssistantFromModelData(Object modelData, ObjectMapper mapper) {
        if (modelData == null || mapper == null) {
            return "";
        }
        return syncAssistantText(mapper.valueToTree(modelData), mapper);
    }

    private static String syncAssistantText(JsonNode data, ObjectMapper mapper) {
        if (data == null) {
            return "";
        }
        try {
            JsonNode choices = data.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return "";
            }
            JsonNode msg = choices.get(0).get("message");
            if (msg == null || !msg.has("content") || msg.get("content").isNull()) {
                return "";
            }
            JsonNode c = msg.get("content");
            if (c.isTextual()) {
                return c.asText("");
            }
            return mapper.writeValueAsString(c);
        }
        catch (Exception e) {
            return "";
        }
    }
}
