package com.example.doubaoai.chatservice.web.dto;

import java.time.Instant;

import com.example.doubaoai.chatservice.domain.ChatRole;

public record MessageDto(
        String id,
        ChatRole role,
        String content,
        Instant createdAt,
        String metadata) {
}
