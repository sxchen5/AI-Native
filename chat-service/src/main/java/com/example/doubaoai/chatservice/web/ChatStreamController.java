package com.example.doubaoai.chatservice.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.doubaoai.chatservice.domain.ChatSession;
import com.example.doubaoai.chatservice.domain.StoredMessage;
import com.example.doubaoai.chatservice.service.ChatAiStreamService;
import com.example.doubaoai.chatservice.store.InMemoryChatStore;
import com.example.doubaoai.chatservice.web.dto.ChatStreamRequest;
import com.example.doubaoai.chatservice.web.dto.SseEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import reactor.core.Disposable;

/**
 * 流式对话：POST + SSE 返回，事件为 JSON（便于前端解析）。
 */
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatStreamController {

    private final InMemoryChatStore store;
    private final ChatAiStreamService aiStreamService;
    private final ObjectMapper objectMapper;

    public ChatStreamController(InMemoryChatStore store, ChatAiStreamService aiStreamService, ObjectMapper objectMapper) {
        this.store = store;
        this.aiStreamService = aiStreamService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        ChatSession session = store.find(request.sessionId()).orElse(null);
        if (session == null) {
            try {
                emitter.send(SseEmitter.event()
                        .data(objectMapper.writeValueAsString(SseEventDto.error("会话不存在"))));
            }
            catch (IOException ignored) {
                // ignore
            }
            emitter.complete();
            return emitter;
        }

        // 持久化用户消息，并准备助手占位消息（前端可立即展示“加载中”）
        store.appendUserMessage(request.sessionId(), request.content());
        var assistantPlaceholder = store.appendAssistantPlaceholder(request.sessionId());
        maybeAutoTitle(session, request.content());

        List<StoredMessage> historyBefore = new ArrayList<>(session.historySnapshot());
        // 去掉本轮用户消息与助手占位：Prompt 中用户输入由 latestUserText 单独传入
        int n = historyBefore.size();
        if (n >= 2) {
            historyBefore.subList(n - 2, n).clear();
        }

        StringBuilder full = new StringBuilder();

        try {
            emitter.send(SseEmitter.event()
                    .data(objectMapper.writeValueAsString(SseEventDto.start(assistantPlaceholder.id()))));
        }
        catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        Disposable sub = aiStreamService.streamReply(historyBefore, request.content())
                .subscribe(chunk -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    full.append(chunk);
                    try {
                        emitter.send(SseEmitter.event()
                                .data(objectMapper.writeValueAsString(SseEventDto.delta(chunk))));
                    }
                    catch (IOException ex) {
                        emitter.completeWithError(ex);
                    }
                }, err -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .data(objectMapper.writeValueAsString(SseEventDto.error(err.getMessage()))));
                    }
                    catch (IOException ignored) {
                        // ignore
                    }
                    emitter.completeWithError(err);
                }, () -> {
                    store.updateAssistantContent(request.sessionId(), assistantPlaceholder.id(), full.toString());
                    try {
                        emitter.send(SseEmitter.event()
                                .data(objectMapper.writeValueAsString(SseEventDto.done())));
                    }
                    catch (IOException ignored) {
                        // ignore
                    }
                    emitter.complete();
                });
        emitter.onCompletion(sub::dispose);
        emitter.onTimeout(sub::dispose);
        emitter.onError(e -> sub.dispose());

        return emitter;
    }

    private void maybeAutoTitle(ChatSession session, String firstUserText) {
        if (!"新对话".equals(session.title())) {
            return;
        }
        List<StoredMessage> msgs = session.messagesView();
        if (msgs.size() != 2) {
            return;
        }
        String t = firstUserText.strip();
        if (t.length() > 24) {
            t = t.substring(0, 24) + "…";
        }
        if (!t.isBlank()) {
            session.setTitle(t);
        }
    }
}
