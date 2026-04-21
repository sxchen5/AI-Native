package com.example.doubaoai.chatservice.web.dto;

import java.util.List;

public record SessionListResponse(List<SessionSummaryDto> items, boolean hasMore) {
}
