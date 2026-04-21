package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param vote {@code up}、{@code down}，或 {@code clear} 表示取消
 */
public record MessageFeedbackRequest(
        @NotBlank String sessionId,
        @NotBlank String messageId,
        @NotBlank String vote) {
}
