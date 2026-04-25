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
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.doubaoai.chatservice.config.ZhipuAiProperties;
import com.example.doubaoai.chatservice.domain.ChatRole;
import com.example.doubaoai.chatservice.domain.StoredMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话流：默认 Spring AI（OpenAI 兼容）；{@code app.zhipu.enabled=true} 时使用智谱 {@code zai-sdk}。
 */
@Service
public class ChatAiStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatAiStreamService.class);

    private final ChatClient chatClient;
    private final UserMessageComposer userMessageComposer;
    private final ObjectMapper objectMapper;
    private final ZhipuAiProperties zhipuAiProperties;
    private final ObjectProvider<ZhipuAiClient> zhipuAiClient;

    public ChatAiStreamService(ChatClient chatClient,
            UserMessageComposer userMessageComposer,
            ObjectMapper objectMapper,
            ZhipuAiProperties zhipuAiProperties,
            ObjectProvider<ZhipuAiClient> zhipuAiClient) {
        this.chatClient = chatClient;
        this.userMessageComposer = userMessageComposer;
        this.objectMapper = objectMapper;
        this.zhipuAiProperties = zhipuAiProperties;
        this.zhipuAiClient = zhipuAiClient;
    }

    public Flux<String> streamReply(List<StoredMessage> historyBeforeCurrentUser, String latestDisplayText,
            String latestModelContextJson) {
        if (useZhipu()) {
            return zhipuStream(buildZhipuMessages(historyBeforeCurrentUser, latestDisplayText, latestModelContextJson, false));
        }
        List<Message> messages = buildSpringMessages(historyBeforeCurrentUser, latestDisplayText, latestModelContextJson, false);
        return streamFromSpring(messages);
    }

    public Flux<String> streamReplyAppend(List<StoredMessage> historyExcludingLatestUserAndPlaceholder,
            String latestDisplayText,
            String latestModelContextJson) {
        if (useZhipu()) {
            return zhipuStream(buildZhipuMessages(historyExcludingLatestUserAndPlaceholder, latestDisplayText, latestModelContextJson, true));
        }
        List<Message> messages = buildSpringMessages(historyExcludingLatestUserAndPlaceholder, latestDisplayText, latestModelContextJson, true);
        return streamFromSpring(messages);
    }

    public Mono<String> generateShortText(String systemPrompt, String userPrompt) {
        return generateShortText(systemPrompt, userPrompt, 0.5);
    }

    /**
     * 短文本生成；{@code temperature} 较高时输出更随机（如落地页开场问题）。
     */
    public Mono<String> generateShortText(String systemPrompt, String userPrompt, double temperature) {
        if (useZhipu()) {
            return zhipuShortText(systemPrompt, userPrompt, (float) temperature);
        }
        Prompt prompt = new Prompt(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt));
        var opts = DefaultToolCallingChatOptions.builder()
                .temperature(temperature)
                .build();
        return chatClient.prompt(prompt)
                .options(opts)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .collectList()
                .map(parts -> String.join("", parts))
                .doOnError(e -> log.warn("短文本生成失败: {}", e.toString()))
                .onErrorReturn("");
    }

    private boolean useZhipu() {
        return zhipuAiProperties.isEnabled() && zhipuAiClient.getIfAvailable() != null;
    }

    private Flux<String> streamFromSpring(List<Message> messages) {
        Prompt prompt = new Prompt(messages);
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("模型流式输出失败", e));
    }

    private List<Message> buildSpringMessages(List<StoredMessage> history, String latestDisplay, String latestContextJson,
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

    private List<ChatMessage> buildZhipuMessages(List<StoredMessage> history, String latestDisplay, String latestContextJson,
            boolean longContext) {
        List<ChatMessage> list = new ArrayList<>();
        String sys = longContext
                ? "你是中文助手。请结合完整上文直接回答问题；如上文过长可聚焦用户最新问题。"
                : "你是中文助手。请直接回答问题；如上下文不足请说明。";
        list.add(ChatMessage.builder()
                .role(ChatMessageRole.SYSTEM.value())
                .content(sys)
                .build());
        for (StoredMessage m : history) {
            if (m.role() == ChatRole.USER) {
                list.add(ChatMessage.builder()
                        .role(ChatMessageRole.USER.value())
                        .content(userMessageComposer.textForHistoryUser(m))
                        .build());
            }
            else if (m.role() == ChatRole.ASSISTANT) {
                String c = m.content() == null ? "" : m.content();
                list.add(ChatMessage.builder()
                        .role(ChatMessageRole.ASSISTANT.value())
                        .content(c)
                        .build());
            }
        }
        String merged = userMessageComposer.mergeForModel(latestDisplay, latestContextJson);
        list.add(ChatMessage.builder()
                .role(ChatMessageRole.USER.value())
                .content(merged)
                .build());
        return list;
    }

    private Flux<String> zhipuStream(List<ChatMessage> messages) {
        return Flux.<String>create(sink -> {
            ZhipuAiClient client = zhipuAiClient.getIfAvailable();
            if (client == null) {
                sink.error(new IllegalStateException("ZhipuAiClient 未就绪"));
                return;
            }
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(zhipuAiProperties.getModel())
                    .messages(messages)
                    // 必须显式 true：null 时 SDK 走同步接口，会等整段生成完才返回（前端表现为一次性输出）
                    .stream(Boolean.TRUE)
                    .temperature(0.7f)
                    .maxTokens(8192)
                    .build();
            ChatCompletionResponse resp = client.chat().createChatCompletion(params);
            if (!resp.isSuccess()) {
                sink.error(new RuntimeException(resp.getMsg() == null ? "智谱调用失败" : resp.getMsg()));
                return;
            }
            if (resp.getFlowable() == null) {
                sink.complete();
                return;
            }
            resp.getFlowable().subscribe(
                    chunk -> {
                        String d = ZhipuStreamChunkParser.deltaText(chunk, objectMapper);
                        if (d != null && !d.isEmpty()) {
                            sink.next(d);
                        }
                    },
                    sink::error,
                    sink::complete);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> zhipuShortText(String systemPrompt, String userPrompt, float temperature) {
        return Mono.fromCallable(() -> {
            ZhipuAiClient client = zhipuAiClient.getIfAvailable();
            if (client == null) {
                return "";
            }
            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().role(ChatMessageRole.SYSTEM.value()).content(systemPrompt).build(),
                    ChatMessage.builder().role(ChatMessageRole.USER.value()).content(userPrompt).build());
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(zhipuAiProperties.getModel())
                    .messages(messages)
                    .stream(false)
                    .temperature(temperature)
                    .maxTokens(1024)
                    .build();
            ChatCompletionResponse resp = client.chat().createChatCompletion(params);
            if (!resp.isSuccess() || resp.getData() == null) {
                log.warn("智谱短文本失败: {}", resp.getMsg());
                return "";
            }
            return ZhipuStreamChunkParser.syncAssistantFromModelData(resp.getData(), objectMapper);
        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("短文本生成失败: {}", e.toString()))
                .onErrorReturn("");
    }
}
