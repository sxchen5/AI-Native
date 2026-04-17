package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @NotBlank String sessionId,
        @NotBlank @Size(max = 16000) String content) {
}
