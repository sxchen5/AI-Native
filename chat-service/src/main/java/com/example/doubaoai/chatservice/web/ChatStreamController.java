package com.example.doubaoai.chatservice.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.example.doubaoai.chatservice.service.ChatTitleAiService;
import com.example.doubaoai.chatservice.service.UserMessageComposer;
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

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    private final InMemoryChatStore store;
    private final ChatAiStreamService aiStreamService;
    private final UserMessageComposer userMessageComposer;
    private final ChatTitleAiService titleAiService;
    private final ObjectMapper objectMapper;

    public ChatStreamController(InMemoryChatStore store, ChatAiStreamService aiStreamService,
            UserMessageComposer userMessageComposer, ChatTitleAiService titleAiService, ObjectMapper objectMapper) {
        this.store = store;
        this.aiStreamService = aiStreamService;
        this.userMessageComposer = userMessageComposer;
        this.titleAiService = titleAiService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        ChatSession session = store.find(request.sessionId()).orElse(null);
        if (session == null) {
            log.warn("stream rejected: session not found sessionId={}", request.sessionId());
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
        final String ctxJson = request.modelContextJson();
        String displayText;
        final boolean appendMode = appendAfterId != null && !appendAfterId.isBlank();

        log.info("stream start sessionId={} appendMode={} restartId={} appendAfterId={} contentLen={} hasModelCtx={}",
                request.sessionId(),
                appendMode,
                restartId,
                appendAfterId,
                rawContent.length(),
                ctxJson != null && !ctxJson.isBlank());

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
            displayText = rawContent.strip().isEmpty() ? (anchor.content() == null ? "" : anchor.content()) : rawContent.strip();
            if (displayText.isBlank()) {
                displayText = userMessageComposer.fallbackDisplayFromContext(ctxJson);
            }
            if (displayText.isBlank()) {
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
            String modelMerged = userMessageComposer.mergeForModel(displayText, ctxJson);
            String meta = userMessageComposer.buildUserMessageMetadata(ctxJson, modelMerged);
            store.appendUserMessage(request.sessionId(), displayText, meta);
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
            displayText = effective;
            if (displayText == null || displayText.isBlank()) {
                displayText = userMessageComposer.fallbackDisplayFromContext(ctxJson);
            }
            if (displayText == null || displayText.isBlank()) {
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
            String modelMerged = userMessageComposer.mergeForModel(displayText, ctxJson);
            String meta = userMessageComposer.buildUserMessageMetadata(ctxJson, modelMerged);
            store.replaceUserMessageAndTruncateAfter(request.sessionId(), restartId, displayText.strip(), meta);
        }
        else {
            displayText = rawContent.strip();
            if (displayText.isEmpty()) {
                displayText = userMessageComposer.fallbackDisplayFromContext(ctxJson);
            }
            if (displayText.isBlank()) {
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
            String modelMerged = userMessageComposer.mergeForModel(displayText, ctxJson);
            String meta = userMessageComposer.buildUserMessageMetadata(ctxJson, modelMerged);
            store.appendUserMessage(request.sessionId(), displayText, meta);
        }
        final String displayTextFinal = displayText.strip();
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

        Disposable sub = (appendMode
                ? aiStreamService.streamReplyAppend(historyBefore, displayTextFinal, ctxJson)
                : aiStreamService.streamReply(historyBefore, displayTextFinal, ctxJson))
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
                    log.warn("stream error sessionId={} assistantMsgId={}: {}",
                            request.sessionId(),
                            assistantPlaceholder.id(),
                            err.toString());
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
                    maybeUpdateTitleFromConversation(session, displayTextFinal, full.toString());
                    log.info("stream done sessionId={} assistantMsgId={} replyChars={}", request.sessionId(), assistantPlaceholder.id(), full.length());
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
     * 首轮对话完成后，根据用户输入与助手回复调用模型生成会话标题。
     */
    private void maybeUpdateTitleFromConversation(ChatSession session, String userText, String assistantText) {
        if (!isDefaultSessionTitle(session.title())) {
            return;
        }
        String title = titleAiService.summarizeConversationTitle(userText, assistantText);
        if (title.isBlank()) {
            String u = firstLine(userText, 15);
            String a = firstLine(assistantText, 15);
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
                String joined = u + "·" + a;
                title = joined.length() <= 15 ? joined : (u.length() <= 15 ? u : u.substring(0, 14) + "…");
            }
        }
        if (title.length() > 15) {
            title = title.substring(0, 14) + "…";
        }
        if (!title.isBlank()) {
            session.setTitle(title);
        }
    }
}
