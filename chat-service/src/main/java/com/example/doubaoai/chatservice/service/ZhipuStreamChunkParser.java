package com.example.doubaoai.chatservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.z.openapi.service.model.Choice;
import ai.z.openapi.service.model.Delta;
import ai.z.openapi.service.model.ModelData;

/**
 * 将 zai-sdk 流式 {@link ModelData} 块解析为增量文本。
 * <p>
 * 原先仅用 {@code ObjectMapper#valueToTree(chunk)} 走 JSON 树；流式回调传入的是强类型 {@link ModelData}，
 * Jackson 未必能还原为 {@code choices[0].delta.content} 结构，导致中间块解析为空、最后才出现正文（像一次性输出）。
 * 因此优先用 SDK getter 读取，再回退到 JSON 树解析。
 */
public final class ZhipuStreamChunkParser {

    private ZhipuStreamChunkParser() {
    }

    public static String deltaText(Object chunk, ObjectMapper mapper) {
        if (chunk == null || mapper == null) {
            return "";
        }
        if (chunk instanceof ModelData md) {
            String typed = deltaFromModelData(md);
            if (!typed.isEmpty()) {
                return typed;
            }
        }
        return deltaFromJsonTree(chunk, mapper);
    }

    private static String deltaFromModelData(ModelData md) {
        if (md.getChoices() != null && !md.getChoices().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Choice c : md.getChoices()) {
                if (c == null) {
                    continue;
                }
                Delta d = c.getDelta();
                if (d != null) {
                    String content = d.getContent();
                    if (content != null && !content.isEmpty()) {
                        sb.append(content);
                    }
                    else {
                        String reasoning = d.getReasoningContent();
                        if (reasoning != null && !reasoning.isEmpty()) {
                            sb.append(reasoning);
                        }
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        String rootDelta = md.getDelta();
        if (rootDelta != null && !rootDelta.isBlank()) {
            return rootDelta;
        }
        String text = md.getText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        return "";
    }

    private static String deltaFromJsonTree(Object chunk, ObjectMapper mapper) {
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
        if (modelData instanceof ModelData md) {
            if (md.getChoices() != null && !md.getChoices().isEmpty()) {
                Choice c0 = md.getChoices().get(0);
                if (c0 != null && c0.getMessage() != null && c0.getMessage().getContent() != null) {
                    Object co = c0.getMessage().getContent();
                    if (co instanceof String s) {
                        return s;
                    }
                }
            }
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
