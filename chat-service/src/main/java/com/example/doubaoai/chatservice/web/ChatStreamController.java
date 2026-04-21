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

import com.example.doubaoai.chatservice.domain.ChatRole;
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

        final String restartId = request.restartFromUserMessageId();
        final String appendAfterId = request.appendAfterUserMessageId();
        final String rawContent = request.content() == null ? "" : request.content();
        final String userText;
        final boolean appendMode = appendAfterId != null && !appendAfterId.isBlank();

        if (appendMode) {
            if (restartId != null && !restartId.isBlank()) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(SseEventDto.error("不能同时使用重新发送与追加模式"))));
                }
                catch (IOException ignored) {
                    // ignore
                }
                emitter.complete();
                return emitter;
            }
            List<StoredMessage> snap = session.historySnapshot();
            StoredMessage anchor = snap.stream()
                    .filter(m -> m.id().equals(appendAfterId))
                    .findFirst()
                    .orElse(null);
            if (anchor == null || anchor.role() != ChatRole.USER) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(SseEventDto.error("锚点用户消息不存在"))));
                }
                catch (IOException ignored) {
                    // ignore
                }
                emitter.complete();
                return emitter;
            }
            userText = rawContent.strip();
            if (userText.isEmpty()) {
                userText = anchor.content() == null ? "" : anchor.content();
            }
            if (userText.isBlank()) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(SseEventDto.error("内容不能为空"))));
                }
                catch (IOException ignored) {
                    // ignore
                }
                emitter.complete();
                return emitter;
            }
            store.appendUserMessage(request.sessionId(), userText, anchor.metadata());
        }
        else if (restartId != null && !restartId.isBlank()) {
            List<StoredMessage> snap = session.historySnapshot();
            StoredMessage userMsg = snap.stream()
                    .filter(m -> m.id().equals(restartId))
                    .findFirst()
                    .orElse(null);
            if (userMsg == null || userMsg.role() != ChatRole.USER) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(SseEventDto.error("用户消息不存在"))));
                }
                catch (IOException ignored) {
                    // ignore
                }
                emitter.complete();
                return emitter;
            }
            String effective = rawContent.strip();
            if (effective.isEmpty()) {
                effective = userMsg.content();
            }
            userText = effective;
            store.replaceUserMessageAndTruncateAfter(request.sessionId(), restartId, userText);
        }
        else {
            userText = rawContent.strip();
            if (userText.isEmpty()) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(SseEventDto.error("内容不能为空"))));
                }
                catch (IOException ignored) {
                    // ignore
                }
                emitter.complete();
                return emitter;
            }
            store.appendUserMessage(request.sessionId(), userText);
        }
        var assistantPlaceholder = store.appendAssistantPlaceholder(request.sessionId());

        List<StoredMessage> historyBefore;
        if (appendMode) {
            historyBefore = new ArrayList<>(session.historySnapshot());
            int n = historyBefore.size();
            if (n >= 2) {
                historyBefore = new ArrayList<>(historyBefore.subList(0, n - 2));
            }
        }
        else {
            historyBefore = new ArrayList<>(session.historySnapshot());
            int n = historyBefore.size();
            if (n >= 2) {
                historyBefore.subList(n - 2, n).clear();
            }
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

        Disposable sub = (appendMode ? aiStreamService.streamReplyAppend(historyBefore, userText) : aiStreamService.streamReply(historyBefore, userText))
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
                    maybeUpdateTitleFromConversation(session, userText, full.toString());
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

    private static boolean isDefaultSessionTitle(String title) {
        if (title == null) {
            return true;
        }
        String s = title.strip();
        return s.isEmpty()
                || "新对话".equals(s)
                || s.equalsIgnoreCase("new chat")
                || s.equalsIgnoreCase("new conversation");
    }

    private static String firstLine(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String one = text.strip().replace('\r', ' ').split("\n")[0].strip();
        if (one.length() > maxLen) {
            return one.substring(0, maxLen) + "…";
        }
        return one;
    }

    /**
     * 首轮对话完成后，根据用户问题与助手摘要生成会话标题（避免长期显示「新对话」）。
     */
    private void maybeUpdateTitleFromConversation(ChatSession session, String userText, String assistantText) {
        if (!isDefaultSessionTitle(session.title())) {
            return;
        }
        String u = firstLine(userText, 20);
        String a = firstLine(assistantText, 16);
        String title;
        if (u.isBlank() && a.isBlank()) {
            return;
        }
        if (a.isBlank()) {
            title = u;
        }
        else if (u.isBlank()) {
            title = a;
        }
        else {
            title = u + " · " + a;
            if (title.length() > 40) {
                title = title.substring(0, 37) + "…";
            }
        }
        if (!title.isBlank()) {
            session.setTitle(title);
        }
    }
}
