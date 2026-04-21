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

    public ChatAiStreamService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 将历史消息（不含本轮用户输入）与本轮用户输入组装为 Prompt，返回模型增量输出流。
     */
    public Flux<String> streamReply(List<StoredMessage> historyBeforeCurrentUser, String latestUserText) {
        List<Message> messages = buildChatMessages(historyBeforeCurrentUser, latestUserText, false);
        Prompt prompt = new Prompt(messages);
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("模型流式输出失败", e));
    }

    /**
     * 追加新用户消息时使用：不把历史截断为「仅上一轮」，而是使用去掉末尾「用户+空助手占位」后的完整上文。
     */
    public Flux<String> streamReplyAppend(List<StoredMessage> historyExcludingLatestUserAndPlaceholder, String latestUserText) {
        List<Message> messages = buildChatMessages(historyExcludingLatestUserAndPlaceholder, latestUserText, true);
        Prompt prompt = new Prompt(messages);
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("模型流式输出失败", e));
    }

    private static List<Message> buildChatMessages(List<StoredMessage> history, String latestUserText, boolean longContext) {
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
                messages.add(new UserMessage(m.content()));
            }
            else if (m.role() == ChatRole.ASSISTANT) {
                messages.add(new AssistantMessage(m.content()));
            }
        }
        messages.add(new UserMessage(latestUserText));
        return messages;
    }

    /**
     * 非流式短文本生成（如猜你想问），失败时返回空串。
     */
    public Mono<String> generateShortText(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt));
        return Mono.fromCallable(() -> chatClient.prompt(prompt).call().content())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("短文本生成失败: {}", e.toString()))
                .onErrorReturn("");
    }
}
