package com.example.doubaoai.chatservice.web.dto;

import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @Size(max = 64) String title) {
}
