package com.smartcampus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.entity.AiConversation;
import com.smartcampus.repository.AiConversationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class QianWenService {

    private final WebClient webClient;
    private final AiConversationRepository aiConversationRepository;
    private final ObjectMapper objectMapper;
    private final ConversationContextService contextService;  // 新增

    // 存储会话历史（可选，现在用 contextService 替代）
    private final Map<String, List<Map<String, String>>> sessionHistories = new ConcurrentHashMap<>();

    public QianWenService(AiConversationRepository aiConversationRepository,
                          ConversationContextService contextService) {  // 新增参数
        this.aiConversationRepository = aiConversationRepository;
        this.contextService = contextService;  // 新增
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
     * 从数据库获取并构建历史消息
     */
    public List<Map<String, String>> buildHistoryFromDb(Long userId, String sessionId, int limit) {
        List<AiConversation> recentHistory = aiConversationRepository
                .findTopNByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId, limit);

        List<Map<String, String>> historyMessages = new ArrayList<>();
        for (AiConversation conv : recentHistory) {
            historyMessages.add(Map.of("role", "user", "content", conv.getQuestion()));
            historyMessages.add(Map.of("role", "assistant", "content", conv.getAnswer()));
        }
        return historyMessages;
    }

    /**
     * 带上下文的流式调用 - 增强版（使用混合方案）
     */
    public Flux<String> askQuestionWithContext(Long userId, String sessionId,
                                               String question,
                                               List<Map<String, String>> extraHistory,
                                               String model,
                                               Long fileId) {  // 新增 fileId 参数

        // 1. 使用 ConversationContextService 构建完整的上下文
        List<Map<String, String>> messages = contextService.buildFullContext(
                userId, sessionId, question, fileId
        );

        // 2. 如果有额外历史（兼容旧逻辑），合并
        if (extraHistory != null && !extraHistory.isEmpty()) {
            // 找到 user 消息的位置，在它之前插入额外历史
            // 简化处理：直接添加到 messages 开头（系统消息之后）
            List<Map<String, String>> merged = new ArrayList<>();
            merged.add(messages.getFirst()); // 系统消息
            merged.addAll(extraHistory);
            merged.addAll(messages.subList(1, messages.size()));
            messages = merged;
        }

        log.info("🤖 调用通义千问，消息数量: {}, 历史轮数: {}",
                messages.size(), messages.size() - 2); // 减去系统消息和当前问题

        // 3. 调用API
        return callAiApi(messages, model);
    }

    /**
     * 带上下文的流式调用
     */
    public Flux<String> askQuestionWithContext(Long userId, String sessionId,
                                               String question,
                                               List<Map<String, String>> extraHistory,
                                               String model) {
        // 1. 从数据库获取历史
        List<Map<String, String>> dbHistory = buildHistoryFromDb(userId, sessionId, 10);

        // 2. 合并额外历史
        List<Map<String, String>> allHistory = new ArrayList<>(dbHistory);
        if (extraHistory != null) {
            allHistory.addAll(extraHistory);
        }

        // 3. 构建 messages
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.addAll(allHistory);
        messages.add(Map.of("role", "user", "content", question));

        log.info("🤖 调用通义千问，消息数量: {}", messages.size());

        // 4. 调用API
        return callAiApi(messages, model);
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