package com.smartcampus.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import jakarta.annotation.PostConstruct;

@Configuration
public class AiConfig {

    @Value("${ai.qianwen.api-key}")
    private String apiKey;

    @Value("${ai.qianwen.api-url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String apiUrl;

    @PostConstruct
    public void checkConfig() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("AI API Key 未配置！请在环境变量或配置文件中设置 ai.qianwen.api-key");
        }
        if (apiKey.startsWith("sk-") && apiKey.length() > 10) {
            // 只是提示，不阻断启动
            System.out.println("⚠️ 警告：检测到 API Key 格式，请确认是否从环境变量读取而非明文");
        }
    }

    @Bean
    public WebClient qianwenWebClient() {
        return WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024)) // 16MB
                .build();
    }
}