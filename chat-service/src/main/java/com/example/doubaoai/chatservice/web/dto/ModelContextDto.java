package com.example.doubaoai.chatservice.web.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 前端发送的可选上下文：语音转写、附件解析全文等；用户气泡仍展示简短 {@code content}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelContextDto(
        String voiceTranscript,
        List<AttachmentPartDto> attachments,
        UserBubbleDto userBubble) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttachmentPartDto(
            String fileName,
            String mimeType,
            String extractedText) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserBubbleDto(
            List<UserChipDto> chips) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserChipDto(
            String label,
            String kind) {
    }
}
