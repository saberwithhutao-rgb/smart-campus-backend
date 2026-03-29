package com.smartcampus.service;

import com.smartcampus.entity.AiConversation;
import com.smartcampus.entity.LearningFile;
import com.smartcampus.repository.AiConversationRepository;
import com.smartcampus.repository.LearningFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文服务 - 维护短期记忆和文件上下文
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationContextService {

    private final AiConversationRepository conversationRepository;
    private final LearningFileRepository learningFileRepository;
    private final FileProcessingService fileProcessingService;
    private final FileSummaryService fileSummaryService;

    @Value("${ai.context.max-history:5}")
    private int maxHistoryTurns;  // 最多保留5轮完整对话

    // 短期记忆缓存：会话ID -> 最近N轮对话
    private final Map<String, CircularBuffer<ConversationTurn>> shortTermMemory = new ConcurrentHashMap<>();

    /**
     * 构建完整的对话上下文
     *
     * @param userId        用户ID
     * @param sessionId     会话ID
     * @param currentQuestion 当前问题
     * @param currentFileId 当前上传的文件ID（可选）
     * @return 用于AI的消息列表
     */
    public List<Map<String, String>> buildFullContext(Long userId, String sessionId,
                                                      String currentQuestion,
                                                      Long currentFileId) {
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. 系统提示（包含用户文件摘要）
        String systemPrompt = buildSystemPromptWithFiles(userId);
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 2. 短期记忆：最近几轮完整对话
        List<ConversationTurn> recentTurns = getShortTermMemory(sessionId);
        for (ConversationTurn turn : recentTurns) {
            // 增强问题：如果这一轮有文件，加上文件上下文
            String enhancedQuestion = enhanceQuestionWithFile(turn.getQuestion(), turn.getFileId());
            messages.add(Map.of("role", "user", "content", enhancedQuestion));
            messages.add(Map.of("role", "assistant", "content", turn.getAnswer()));
        }

        // 3. 当前问题（如果有文件，增强）
        String enhancedCurrentQuestion = enhanceQuestionWithFile(currentQuestion, currentFileId);
        messages.add(Map.of("role", "user", "content", enhancedCurrentQuestion));

        log.info("构建上下文完成，消息数: {}, 历史轮数: {}", messages.size(), recentTurns.size());

        return messages;
    }

    /**
     * 更新短期记忆
     */
    public void updateShortTermMemory(String sessionId, String question, String answer, Long fileId) {
        CircularBuffer<ConversationTurn> buffer = shortTermMemory.computeIfAbsent(
                sessionId,
                k -> new CircularBuffer<>(maxHistoryTurns)
        );

        buffer.add(new ConversationTurn(question, answer, fileId));
        log.debug("更新短期记忆，会话: {}, 当前大小: {}", sessionId, buffer.size());
    }

    /**
     * 获取短期记忆
     */
    private List<ConversationTurn> getShortTermMemory(String sessionId) {
        CircularBuffer<ConversationTurn> buffer = shortTermMemory.get(sessionId);
        return buffer != null ? buffer.asList() : Collections.emptyList();
    }

    /**
     * 清理会话的短期记忆
     */
    public void clearShortTermMemory(String sessionId) {
        shortTermMemory.remove(sessionId);
        log.debug("清理短期记忆，会话: {}", sessionId);
    }

    /**
     * 构建包含文件摘要的系统提示词
     */
    private String buildSystemPromptWithFiles(Long userId) {
        String basePrompt = """
                你是智慧校园的个性化学习伴侣，负责解答学生关于学习、课程、校园生活等方面的问题。
                
                回答要求：
                1. 专业、准确、友好
                2. 如果用户上传了文件或提到了之前上传的文件，请基于文件内容回答
                3. 如果资料不足，可以结合常识回答但需说明
                4. 回答格式清晰，适当使用分段和重点标注
                5. 如果用户的问题需要参考历史对话，请结合上下文回答
                """;

        // 获取用户的所有文件摘要
        String filesSummary = fileSummaryService.getAllUserFilesSummary(userId);

        if (filesSummary != null && !filesSummary.isEmpty()) {
            return basePrompt + "\n\n" + filesSummary + "\n\n" +
                    "用户可能随时询问这些文件的内容，请根据文件摘要和实际内容回答。";
        }

        return basePrompt;
    }

    /**
     * 增强问题：如果有关联文件，添加文件内容
     */
    private String enhanceQuestionWithFile(String question, Long fileId) {
        if (fileId == null) {
            return question;
        }

        try {
            // 尝试从数据库获取文件
            Optional<LearningFile> fileOpt = learningFileRepository.findById(fileId);
            if (fileOpt.isPresent()) {
                LearningFile file = fileOpt.get();

                // 如果文件内容已经缓存过，直接使用
                String fileContent = getCachedFileContent(fileId, file.getFilePath());

                if (fileContent != null && !fileContent.isEmpty()) {
                    // 限制内容长度
                    String limitedContent = fileContent.length() > 1500 ?
                            fileContent.substring(0, 1500) + "\n...(内容过长，已截断)" : fileContent;

                    return question + "\n\n【参考文件：" + file.getOriginalName() + "】\n" + limitedContent;
                } else {
                    // 如果内容读取失败，只提供文件名
                    return question + "\n\n【曾上传文件：" + file.getOriginalName() + "，但内容读取失败】";
                }
            }
        } catch (Exception e) {
            log.error("增强问题失败: fileId={}", fileId, e);
        }

        return question;
    }

    // 简单的文件内容缓存
    private final Map<Long, String> fileContentCache = new ConcurrentHashMap<>();

    /**
     * 获取缓存的文件内容（带重试机制）
     */
    private String getCachedFileContent(Long fileId, String filePath) {
        // 先查缓存
        if (fileContentCache.containsKey(fileId)) {
            return fileContentCache.get(fileId);
        }

        int maxRetries = 3;
        int retryDelayMs = 200; // 重试间隔 200ms

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                String content = fileProcessingService.extractTextFromFileByPath(filePath);

                // 检查是否解析成功（不包含错误标记）
                if (content != null && !content.isEmpty() &&
                        !content.startsWith("【文件解析失败") &&
                        !content.startsWith("【文件不存在") &&
                        !content.startsWith("【不支持的文件格式")) {

                    fileContentCache.put(fileId, content);
                    if (retry > 0) {
                        log.info("文件读取成功，重试次数: {}/{}", retry + 1, maxRetries);
                    }
                    return content;
                }

                // 如果是解析失败但不是错误信息，也缓存
                if (content != null && !content.isEmpty()) {
                    log.warn("文件内容可能有问题: {}", content.substring(0, Math.min(100, content.length())));
                }

            } catch (Exception e) {
                log.warn("读取文件失败，重试 {}/{}: fileId={}, error={}",
                        retry + 1, maxRetries, fileId, e.getMessage());
            }

            // 最后一次重试失败，不再等待
            if (retry < maxRetries - 1) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("文件读取重试被中断");
                    break;
                }
            }
        }

        log.error("文件读取最终失败: fileId={}, filePath={}", fileId, filePath);
        return null;
    }

    /**
     * 对话轮次（用于短期记忆）
     */
    @lombok.Value
    private static class ConversationTurn {
        String question;
        String answer;
        Long fileId;
    }

    /**
     * 循环缓冲区
     */
    private static class CircularBuffer<T> {
        private final List<T> buffer;
        private final int maxSize;
        private int head = 0;
        private int size = 0;

        public CircularBuffer(int maxSize) {
            this.maxSize = maxSize;
            this.buffer = new ArrayList<>(maxSize);
            for (int i = 0; i < maxSize; i++) {
                buffer.add(null);
            }
        }

        public synchronized void add(T item) {
            buffer.set(head, item);
            head = (head + 1) % maxSize;
            if (size < maxSize) {
                size++;
            }
        }

        public synchronized int size() {
            return size;
        }

        public synchronized List<T> asList() {
            List<T> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int index = (head - size + i + maxSize) % maxSize;
                T item = buffer.get(index);
                if (item != null) {
                    result.add(item);
                }
            }
            return result;
        }
    }
}