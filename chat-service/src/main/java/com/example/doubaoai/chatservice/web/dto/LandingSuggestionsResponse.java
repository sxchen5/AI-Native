package com.example.doubaoai.chatservice.web.dto;

import java.util.List;

/** 新对话落地页：AI 生成的中文推荐问句 */
public record LandingSuggestionsResponse(List<String> questions) {
}
