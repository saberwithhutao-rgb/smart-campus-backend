package com.smartcampus.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;

@Service
public class RagService {

    public String answerWithRAG(String question, String userId) {
        // 你的原有RAG逻辑...
        // 这里简化为直接调用通义千问
        return "这是基于RAG生成的回答...";
    }

    // 新增流式输出方法
    public Flux<String> answerWithRAGStream(String question, String userId) {
        return Flux.create(emitter -> {
            try {
                // 模拟流式输出
                String fullAnswer = answerWithRAG(question, userId);
                String[] words = fullAnswer.split("");

                for (int i = 0; i < words.length; i++) {
                    if (!emitter.isCancelled()) {
                        emitter.next(words[i]);
                        Thread.sleep(50); // 控制输出速度
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.error(e);
            }
        });
    }
}