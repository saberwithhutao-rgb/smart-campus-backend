package com.smartcampus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class QianWenService {

    @Autowired
    private WebClient qianwenWebClient;

    @Autowired
    private ObjectMapper objectMapper;

    public Mono<String> askQuestion(String question, List<String> contexts, String model) {
        // 构建消息
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", buildUserPrompt(question, contexts))
        );

        Map<String, Object> requestBody = Map.of(
                "model", model != null ? model : "qwen-max",
                "messages", messages,
                "temperature", 0.3,
                "top_p", 0.8,
                "max_tokens", 2000
        );

        long startTime = System.currentTimeMillis();

        return qianwenWebClient.post()
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    try {
                        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            Map<String, Object> choice = choices.get(0);
                            Map<String, String> message = (Map<String, String>) choice.get("message");
                            String content = message.get("content");

                            long responseTime = System.currentTimeMillis() - startTime;
                            log.info("通义千问API调用成功，耗时: {}ms", responseTime);

                            return Mono.just(content);
                        }
                        return Mono.error(new RuntimeException("API响应格式异常"));
                    } catch (Exception e) {
                        log.error("解析API响应失败", e);
                        return Mono.error(e);
                    }
                })
                .timeout(Duration.ofSeconds(90))
                .onErrorResume(e -> {
                    log.error("调用通义千问API失败: {}", e.getMessage());
                    // 降级策略：返回友好提示
                    return Mono.just("抱歉，AI服务暂时不可用。请稍后再试或联系管理员。错误信息：" + e.getMessage());
                });
    }

    private String buildSystemPrompt() {
        return "你是智慧校园的个性化学习伴侣，负责解答学生关于学习、课程、校园生活等方面的问题。\n" +
                "回答要求：\n" +
                "1. 专业、准确、友好\n" +
                "2. 如果提供参考资料，请基于资料回答\n" +
                "3. 如果资料不足，可以结合常识回答但需说明\n" +
                "4. 回答格式清晰，适当使用分段和重点标注";
    }

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
}