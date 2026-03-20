package com.smartcampus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.repository.AiConversationRepository;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class QianWenService {

    private final WebClient webClient;
    @Getter
    private final AiConversationRepository aiConversationRepository;
    private final ObjectMapper objectMapper;

    public QianWenService(AiConversationRepository aiConversationRepository) {
        this.aiConversationRepository = aiConversationRepository;
        this.objectMapper = new ObjectMapper();

        String apiKey = System.getenv("AI_QIANWEN_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("AI_QIANWEN_API_KEY 环境变量未设置");
            throw new RuntimeException("AI_QIANWEN_API_KEY 环境变量未设置");
        }

        this.webClient = WebClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /**
     * 带上下文的流式调用 - 直接接收已构建好的消息列表
     */
    public Flux<String> askQuestionWithContext(List<Map<String, String>> messages, String model) {
        log.info("🤖 调用通义千问，消息数量: {}", messages.size());
        return callAiApi(messages, model);
    }

    // 保留旧方法，标记为废弃
    @Deprecated
    public Flux<String> askQuestionWithContext(Long userId, String sessionId,
                                               String question,
                                               List<Map<String, String>> extraHistory,
                                               String model) {
        // 为了兼容性，但实际不会调用
        log.warn("使用了废弃的 askQuestionWithContext 方法");
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", question)
        );
        return callAiApi(messages, model);
    }

    @Deprecated
    public Flux<String> askQuestionWithContext(Long userId, String sessionId,
                                               String question,
                                               List<Map<String, String>> extraHistory,
                                               String model,
                                               Long fileId) {
        return askQuestionWithContext(userId, sessionId, question, extraHistory, model);
    }

    /**
     * 调用通义千问API（流式）
     */
    private Flux<String> callAiApi(List<Map<String, String>> messages, String model) {
        Map<String, Object> requestBody = Map.of(
                "model", model != null ? model : "qwen-max",
                "messages", messages,
                "temperature", 0.3,
                "top_p", 0.8,
                "max_tokens", 2000,
                "stream", true,
                "incremental_output", true
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(90))
                .doOnError(e -> log.error("调用通义千问失败", e))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(5))
                        .filter(throwable -> throwable.getMessage() != null &&
                                (throwable.getMessage().contains("reset") ||
                                        throwable.getMessage().contains("timeout")))
                        .doBeforeRetry(retrySignal ->
                                log.info("第 {} 次重试, 原因: {}",
                                        retrySignal.totalRetries() + 1,
                                        retrySignal.failure().getMessage()))
                );
    }

    /**
     * 非流式调用 - 保留用于兼容性和测试
     */
    public Mono<String> askQuestion(String question, List<String> contexts, String model) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildUserPrompt(question, contexts))
        );

        Map<String, Object> requestBody = Map.of(
                "model", model != null ? model : "qwen-max",
                "messages", messages,
                "temperature", 0.3,
                "top_p", 0.8,
                "max_tokens", 2000,
                "stream", false
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseNonStreamResponse)
                .timeout(Duration.ofSeconds(90))
                .doOnError(e -> log.error("调用通义千问API失败: {}", e.getMessage()));
    }

    /**
     * 解析非流式响应
     */
    private String parseNonStreamResponse(String response) {
        try {
            Map responseMap = objectMapper.readValue(response, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, String> message = (Map<String, String>) choice.get("message");
                return message.get("content");
            }
            return "AI服务响应格式异常";
        } catch (Exception e) {
            log.error("解析API响应失败", e);
            return "解析响应失败";
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return """
                你是智慧校园的个性化学习伴侣，负责解答学生关于学习、课程、校园生活等方面的问题。
                回答要求：
                1. 专业、准确、友好
                2. 如果提供参考资料，请基于资料回答
                3. 如果资料不足，可以结合常识回答但需说明
                4. 回答格式清晰，适当使用分段和重点标注""";
    }

    /**
     * 构建用户提示词（带上下文）
     */
    private String buildUserPrompt(String question, List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return question;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("请参考以下资料回答问题：\n\n");
        for (int i = 0; i < contexts.size(); i++) {
            prompt.append("【参考资料").append(i + 1).append("】\n");
            prompt.append(contexts.get(i)).append("\n\n");
        }
        prompt.append("问题：").append(question).append("\n\n");
        prompt.append("请根据以上资料给出详细解答。");
        return prompt.toString();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

}