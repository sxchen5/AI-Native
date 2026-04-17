package com.example.doubaoai.chatservice.web.dto;

import java.time.Instant;

public record SessionSummaryDto(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt) {
}
