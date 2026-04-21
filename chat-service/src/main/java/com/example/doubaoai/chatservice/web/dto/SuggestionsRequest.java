package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SuggestionsRequest(@NotBlank String sessionId) {
}
