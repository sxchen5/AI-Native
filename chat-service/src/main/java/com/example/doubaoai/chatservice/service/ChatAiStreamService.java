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
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("""
                你是中文助手。请直接回答问题；如上下文不足请说明。
                """));
        for (StoredMessage m : historyBeforeCurrentUser) {
            if (m.role() == ChatRole.USER) {
                messages.add(new UserMessage(m.content()));
            }
            else if (m.role() == ChatRole.ASSISTANT) {
                messages.add(new AssistantMessage(m.content()));
            }
            // SYSTEM 历史消息忽略（已在上方统一注入）
        }
        messages.add(new UserMessage(latestUserText));

        Prompt prompt = new Prompt(messages);
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("模型流式输出失败", e));
    }
}
