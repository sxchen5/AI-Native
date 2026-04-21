package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;

public record FreezeDocumentRequest(
        @NotBlank String sessionId,
        @NotBlank String messageId) {
}
