package com.example.doubaoai.chatservice.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.example.doubaoai.chatservice.domain.ChatRole;
import com.example.doubaoai.chatservice.domain.StoredMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Spring AI 2.0：使用 {@link Prompt}、{@link UserMessage}、{@link SystemMessage} 与 {@link ChatClient#prompt(Prompt)}，
 * 并通过 {@code stream().content()} 获取增量文本流。
 */
@Service
public class ChatAiStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatAiStreamService.class);

    private final ChatClient chatClient;
    private final UserMessageComposer userMessageComposer;

    public ChatAiStreamService(ChatClient chatClient, UserMessageComposer userMessageComposer) {
        this.chatClient = chatClient;
        this.userMessageComposer = userMessageComposer;
    }

    public Flux<String> streamReply(List<StoredMessage> historyBeforeCurrentUser, String latestDisplayText,
            String latestModelContextJson) {
        List<Message> messages = buildMessages(historyBeforeCurrentUser, latestDisplayText, latestModelContextJson, false);
        return streamFromMessages(messages);
    }

    public Flux<String> streamReplyAppend(List<StoredMessage> historyExcludingLatestUserAndPlaceholder,
            String latestDisplayText,
            String latestModelContextJson) {
        List<Message> messages = buildMessages(historyExcludingLatestUserAndPlaceholder, latestDisplayText, latestModelContextJson, true);
        return streamFromMessages(messages);
    }

    private Flux<String> streamFromMessages(List<Message> messages) {
        Prompt prompt = new Prompt(messages);
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("模型流式输出失败", e));
    }

    private List<Message> buildMessages(List<StoredMessage> history, String latestDisplay, String latestContextJson,
            boolean longContext) {
        List<Message> messages = new ArrayList<>();
        String sys = longContext
                ? """
                        你是中文助手。请结合完整上文直接回答问题；如上文过长可聚焦用户最新问题。
                        """
                : """
                        你是中文助手。请直接回答问题；如上下文不足请说明。
                        """;
        messages.add(new SystemMessage(sys));
        for (StoredMessage m : history) {
            if (m.role() == ChatRole.USER) {
                messages.add(new UserMessage(userMessageComposer.textForHistoryUser(m)));
            }
            else if (m.role() == ChatRole.ASSISTANT) {
                messages.add(new AssistantMessage(m.content()));
            }
        }
        messages.add(userMessageComposer.toModelUserMessage(latestDisplay, latestContextJson));
        return messages;
    }

    /**
     * 非流式短文本（标题、猜你想问等）。部分 OpenAI 兼容网关不支持同步 {@code call()}，会返回
     * {@code 400: current user api does not support http call}，因此改为与主对话相同的 {@code stream().content()} 再聚合。
     */
    public Mono<String> generateShortText(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt));
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .collectList()
                .map(parts -> String.join("", parts))
                .doOnError(e -> log.warn("短文本生成失败: {}", e.toString()))
                .onErrorReturn("");
    }
}
