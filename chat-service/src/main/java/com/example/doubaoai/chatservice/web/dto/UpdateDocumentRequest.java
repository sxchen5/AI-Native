package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDocumentRequest(
        @NotBlank String sessionId,
        @NotBlank String messageId,
        @NotBlank @Size(max = 500_000) String markdownBody) {
}
