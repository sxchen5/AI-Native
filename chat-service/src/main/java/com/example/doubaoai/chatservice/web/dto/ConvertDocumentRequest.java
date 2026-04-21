package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConvertDocumentRequest(
        @NotBlank String sessionId,
        @NotBlank String sourceAssistantMessageId) {
}
