package com.smartcampus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.entity.LearningFile;
import com.smartcampus.repository.LearningFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件摘要服务 - 为上传的文件生成AI摘要
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileSummaryService {

    private final LearningFileRepository learningFileRepository;
    private final FileProcessingService fileProcessingService;
    private final QianWenService qianWenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${ai.rag.top-k:3}")
    private int topK;

    // 缓存文件摘要，避免重复生成
    private final Map<Long, String> summaryCache = new ConcurrentHashMap<>();

    /**
     * 为文件生成摘要（异步）
     */
    public void generateFileSummaryAsync(Long fileId, Long userId) {
        log.info("开始为文件 {} 生成摘要", fileId);

        // ✅ 修正：使用 ifPresent 而不是先获取再判断
        learningFileRepository.findById(fileId).ifPresent(file -> {
            try {
                // 1. 提取文件内容
                String fileContent = fileProcessingService.extractTextFromFileByPath(file.getFilePath());

                if (fileContent == null || fileContent.isEmpty()) {
                    log.warn("文件内容为空，无法生成摘要: {}", fileId);
                    file.setSummary("【文件内容为空】");
                    learningFileRepository.save(file);
                    return;
                }

                // 2. 如果内容太长，取前部分内容（避免token超限）
                String truncatedContent = fileContent.length() > 3000 ?
                        fileContent.substring(0, 3000) + "..." : fileContent;

                // 3. 构建摘要生成提示词
                String prompt = buildSummaryPrompt(file.getOriginalName(), truncatedContent);

                // 4. 调用AI生成摘要
                Mono<String> summaryMono = qianWenService.askQuestion(prompt, List.of(), "qwen-max");

                String summary = summaryMono.block(Duration.ofSeconds(60));

                if (summary != null && !summary.isEmpty()) {
                    // 5. 保存摘要
                    file.setSummary(summary);
                    learningFileRepository.save(file);
                    summaryCache.put(fileId, summary);
                    log.info("文件摘要生成成功: {} -> {}", file.getOriginalName(),
                            summary.length() > 50 ? summary.substring(0, 50) + "..." : summary);
                } else {
                    log.warn("文件摘要生成失败，结果为null: {}", fileId);
                    file.setSummary("【摘要生成失败】");
                    learningFileRepository.save(file);
                }

            } catch (Exception e) {
                log.error("生成文件摘要失败: fileId={}", fileId, e);
                // 保存失败状态
                try {
                    LearningFile fileToUpdate = learningFileRepository.findById(fileId).orElse(null);
                    if (fileToUpdate != null) {
                        fileToUpdate.setSummary("【摘要生成失败: " + e.getMessage() + "】");
                        learningFileRepository.save(fileToUpdate);
                    }
                } catch (Exception ex) {
                    log.error("保存失败状态失败", ex);
                }
            }
        });
    }

    /**
     * 获取文件摘要（带缓存）
     */
    public String getFileSummary(Long fileId) {
        // 先查缓存
        if (summaryCache.containsKey(fileId)) {
            return summaryCache.get(fileId);
        }

        // 查数据库
        return learningFileRepository.findById(fileId)
                .map(file -> {
                    String summary = file.getSummary();
                    if (summary != null) {
                        summaryCache.put(fileId, summary);
                    }
                    return summary != null ? summary : "【文件摘要不可用】";
                })
                .orElse("【文件不存在】");
    }

    /**
     * 构建摘要生成提示词
     */
    private String buildSummaryPrompt(String fileName, String content) {
        return String.format("""
                请为以下学习资料生成一个简洁的摘要（50-100字），突出核心内容和关键知识点。
                
                文件名：%s
                
                内容：
                %s
                
                要求：
                1. 语言简洁明了
                2. 突出核心知识点
                3. 适合学生快速了解资料内容
                4. 不要包含"本文档"、"本文件"等开头语
                
                摘要：
                """, fileName, content);
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
    }
}