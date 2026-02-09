package com.smartcampus.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.entity.AiConversation;
import com.smartcampus.entity.LearningFile;
import com.smartcampus.repository.AiConversationRepository;
import com.smartcampus.repository.LearningFileRepository;
import com.smartcampus.repository.UserRepository;
import com.smartcampus.service.FileProcessingService;
import com.smartcampus.service.QianWenService;
import com.smartcampus.utils.JwtUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiQaController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private QianWenService qianWenService;

    @Autowired
    private FileProcessingService fileProcessingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AiConversationRepository aiConversationRepository;

    @Autowired
    private LearningFileRepository learningFileRepository;

    private ExecutorService executorService;
    private final Map<String, String> taskStatus = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 使用可配置的线程池
        executorService = new ThreadPoolExecutor(
                5, // 核心线程数
                20, // 最大线程数
                60L, TimeUnit.SECONDS, // 空闲时间
                new LinkedBlockingQueue<>(100), // 任务队列
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
        );
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @GetMapping("/chat")
    public ResponseEntity<Void> handleChatPage() {
        log.info("收到GET请求到/ai/chat，重定向到首页");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/"))
                .build();
    }

    /**
     * 统一的智能问答接口（支持流式和非流式）
     * POST /ai/chat
     *
     * 请求参数：
     * - question: 问题内容（必需）
     * - file: 文件（可选）
     * - sessionId: 会话ID（可选）
     * - stream: 是否流式输出（可选，默认false）
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chatWithAi(
            @RequestParam("question") String question,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionIdParam,
            @RequestParam(value = "stream", defaultValue = "false") String streamParam,
            @RequestHeader(value = "Authorization") String authHeader) {

        log.info("AI聊天接口被调用，问题: {}, stream: {}", question, streamParam);

        try {
            // 1. 验证用户
            Long userId = validateAndExtractUserId(authHeader);
            if (userId == null) {
                return ResponseEntity.status(401)
                        .body(Map.of("code", 401, "message", "未授权或Token无效"));
            }

            // 2. 处理会话ID
            String sessionId = (sessionIdParam != null && !sessionIdParam.isEmpty())
                    ? sessionIdParam
                    : generateSessionId();

            // 3. 检查是否流式输出
            boolean stream = "true".equalsIgnoreCase(streamParam) || "1".equals(streamParam);

            if (stream) {
                // 流式输出 - 如果有文件，不支持流式
                if (file != null && !file.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("code", 400, "message", "暂不支持流式输出时上传文件"));
                }
                // 返回SSE流
                return handleStreamChat(question, userId, sessionId);
            } else {
                // 普通输出
                return handleNormalChat(question, file, userId, sessionId);
            }

        } catch (Exception e) {
            log.error("AI接口异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    /**
     * 专门的流式问答接口
     * POST /ai/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionIdParam,
            @RequestHeader("Authorization") String authHeader) {

        // 验证用户
        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未授权");
        }

        String sessionId = (sessionIdParam != null && !sessionIdParam.isEmpty())
                ? sessionIdParam
                : generateSessionId();

        // 创建SSE Emitter
        SseEmitter emitter = new SseEmitter(120000L); // 2分钟超时

        // 设置回调
        emitter.onCompletion(() -> log.info("SSE连接完成: sessionId={}", sessionId));
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时: sessionId={}", sessionId);
            emitter.complete();
        });
        emitter.onError(ex -> {
            log.error("SSE连接错误: sessionId={}", sessionId, ex);
            emitter.completeWithError(ex);
        });

        // 提交处理任务
        executorService.submit(() -> {
            try {
                processStreamResponse(question, userId, sessionId, emitter);
            } catch (Exception e) {
                log.error("流式处理失败", e);
                try {
                    Map<String, Object> error = Map.of(
                            "error", true,
                            "message", "处理失败: " + e.getMessage()
                    );
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(error))
                            .name("error"));
                } catch (Exception ignore) {
                    // 忽略发送错误
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 处理流式聊天响应
     */
    private ResponseEntity<?> handleStreamChat(String question, Long userId, String sessionId) {
        // 创建SSE Emitter并返回
        SseEmitter emitter = new SseEmitter(120000L);

        emitter.onCompletion(() -> log.info("统一接口SSE连接完成"));
        emitter.onTimeout(() -> {
            log.warn("统一接口SSE连接超时");
            emitter.complete();
        });

        executorService.submit(() -> {
            try {
                processStreamResponse(question, userId, sessionId, emitter);
            } catch (Exception e) {
                log.error("流式处理失败", e);
                emitter.completeWithError(e);
            }
        });

        // 注意：这里直接返回SseEmitter，Spring会自动处理SSE响应
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /**
     * 处理普通聊天响应
     */
    private ResponseEntity<?> handleNormalChat(String question, MultipartFile file,
                                               Long userId, String sessionId) {

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");

        Map<String, Object> data = new HashMap<>();

        // 根据是否有文件选择处理方式
        if (file != null && !file.isEmpty()) {
            // 有文件上传的处理
            return handleFileUpload(question, file, userId.toString(), sessionId);
        } else {
            // 纯文本问题 - 调用真正的AI服务
            log.info("调用通义千问API回答问题: {}", question);

            try {
                String aiAnswer = qianWenService.askQuestion(question,
                                Collections.emptyList(),
                                "qwen-max")
                        .block(Duration.ofSeconds(30));

                if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                    aiAnswer = "AI服务返回空响应，请稍后重试。";
                }

                // 如果回答过长，进行截断
                if (aiAnswer.length() > 10000) {
                    log.warn("AI回答过长，进行截断，原长度: {}", aiAnswer.length());
                    aiAnswer = aiAnswer.substring(0, 10000) + "...\n\n（回答过长，已截断）";
                }

                // 异步保存对话记录
                String finalAnswer = aiAnswer;
                executorService.submit(() -> {
                    saveConversationWithRetry(userId.toString(), sessionId, question, finalAnswer, null);
                });

                data.put("answer", aiAnswer);
                data.put("sessionId", sessionId);

            } catch (Exception e) {
                log.error("调用AI服务失败", e);
                String errorMsg = e.getMessage();
                if (errorMsg != null && (errorMsg.contains("Timeout") || errorMsg.contains("超时"))) {
                    data.put("answer", "AI服务响应超时，请稍后重试或减少问题长度。");
                } else {
                    data.put("answer", "AI服务暂时不可用，请稍后重试。");
                }
                data.put("sessionId", sessionId);
            }
        }

        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 流式响应处理核心逻辑
     */
    private void processStreamResponse(String question, Long userId,
                                       String sessionId, SseEmitter emitter) throws Exception {

        log.info("开始流式处理用户 {} 的问题: {}, sessionId: {}", userId, question, sessionId);

        // 调用 AI 服务
        String fullAnswer = qianWenService.askQuestion(question,
                        Collections.emptyList(), "qwen-max")
                .block(Duration.ofSeconds(60));

        if (fullAnswer == null) {
            fullAnswer = "AI服务暂时不可用，请稍后重试。";
        }

        // 限制长度
        if (fullAnswer.length() > 10000) {
            fullAnswer = fullAnswer.substring(0, 10000) + "...";
        }

        log.info("流式输出总长度: {}", fullAnswer.length());

        // 流式输出
        int chunkSize = Math.min(200, Math.max(50, fullAnswer.length() / 40));
        for (int i = 0; i < fullAnswer.length(); i += chunkSize) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            int end = Math.min(i + chunkSize, fullAnswer.length());
            String chunk = fullAnswer.substring(i, end);
            boolean isDone = end >= fullAnswer.length();

            // 构建SSE事件数据
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("chunk", chunk);
            eventData.put("done", isDone);
            eventData.put("sessionId", sessionId);
            eventData.put("progress", (double) end / fullAnswer.length());

            try {
                // 发送SSE格式的数据
                String jsonData = objectMapper.writeValueAsString(eventData);
                emitter.send(SseEmitter.event()
                        .data(jsonData)
                        .id(String.valueOf(i))
                        .name("message"));

                log.debug("发送chunk: {}-{}, 长度: {}", i, end, chunk.length());

            } catch (Exception e) {
                log.warn("发送chunk失败，可能客户端已断开", e);
                break;
            }

            // 控制输出速度
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 异步保存记录
        String finalFullAnswer = fullAnswer;
        executorService.submit(() -> {
            try {
                saveConversationWithRetry(userId.toString(), sessionId, question, finalFullAnswer, null);
                log.info("对话记录保存完成，会话ID: {}", sessionId);
            } catch (Exception e) {
                log.error("保存对话记录失败", e);
            }
        });

        log.info("流式输出完成，会话ID: {}", sessionId);
        emitter.complete();
    }

    /**
     * 验证并提取用户ID
     */
    private Long validateAndExtractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Authorization头格式错误或缺失");
            return null;
        }

        try {
            String token = authHeader.substring(7);
            // 先验证token是否有效
            if (!jwtUtil.validateToken(token)) {
                log.warn("Token验证失败");
                return null;
            }
            return jwtUtil.getUserIdFromToken(token);
        } catch (Exception e) {
            log.error("Token解析失败", e);
            return null;
        }
    }

    /**
     * 生成会话ID
     */
    private String generateSessionId() {
        return "sess_" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 处理文件上传
     */
    private ResponseEntity<?> handleFileUpload(String question, MultipartFile file,
                                               String userId, String sessionId) {
        // 验证文件类型
        String[] allowedTypes = {"pdf", "doc", "docx", "txt", "ppt", "pptx"};
        String originalName = file.getOriginalFilename();
        String fileExt = getFileExtension(originalName).toLowerCase();

        if (!Arrays.asList(allowedTypes).contains(fileExt)) {
            return ResponseEntity.badRequest()
                    .body(buildErrorResponse(400, "不支持的文件格式"));
        }

        // 生成任务ID
        String taskId = "task_" + UUID.randomUUID().toString().substring(0, 8);
        taskStatus.put(taskId, "processing");

        // 异步处理文件
        executorService.submit(() -> {
            try {
                // 1. 保存文件到数据库
                LearningFile learningFile = saveLearningFile(file, userId);

                // 2. 提取文件文本
                String fileContent = fileProcessingService.extractTextFromFile(file);

                // 3. 构建提示词（问题 + 文件内容）
                String enhancedQuestion = question + "\n\n相关文件内容参考:\n" +
                        fileContent.substring(0, Math.min(2000, fileContent.length()));

                // 4. 调用AI
                String aiAnswer = qianWenService.askQuestion(enhancedQuestion,
                                Collections.emptyList(),
                                "qwen-max")
                        .block(Duration.ofSeconds(30));

                // 5. 保存对话记录
                saveConversation(Long.parseLong(userId), sessionId, question, aiAnswer, learningFile.getId());

                // 6. 更新文件摘要
                if (aiAnswer != null) {
                    updateFileSummary(learningFile.getId(), aiAnswer);
                }

                // 7. 更新任务状态
                taskStatus.put(taskId, "completed:" + aiAnswer);

            } catch (Exception e) {
                log.error("文件处理失败", e);
                taskStatus.put(taskId, "failed:" + e.getMessage());
            }
        });

        // 立即返回任务ID（202状态码）
        Map<String, Object> response = new HashMap<>();
        response.put("code", 202);
        response.put("message", "文件正在处理中");
        response.put("data", Map.of(
                "taskId", taskId,
                "sessionId", sessionId,
                "status", "processing"
        ));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 文件解析状态查询
     */
    @GetMapping("/chat/task/{taskId}")
    public ResponseEntity<?> getTaskStatus(@PathVariable String taskId,
                                           @RequestHeader("Authorization") String authHeader) {

        // 验证用户
        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权"));
        }

        String status = taskStatus.get(taskId);

        if (status == null) {
            return ResponseEntity.status(404)
                    .body(buildErrorResponse(404, "任务不存在"));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");

        Map<String, Object> data = new HashMap<>();
        data.put("taskId", taskId);

        if (status.startsWith("completed:")) {
            data.put("status", "completed");
            data.put("answer", status.substring(10));
        } else if (status.startsWith("failed:")) {
            data.put("status", "failed");
            data.put("error", status.substring(7));
        } else {
            data.put("status", "processing");
        }

        response.put("data", data);

        return ResponseEntity.ok(response);
    }

    /**
     * 监控端点，查看任务状态
     */
    @GetMapping("/chat/status")
    public ResponseEntity<?> getChatStatus(@RequestHeader("Authorization") String authHeader) {
        // 验证用户
        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权"));
        }

        Map<String, Object> status = new HashMap<>();
        if (executorService instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor pool = (ThreadPoolExecutor) executorService;
            status.put("activeThreads", pool.getActiveCount());
            status.put("queueSize", pool.getQueue().size());
            status.put("completedTasks", pool.getCompletedTaskCount());
        }
        status.put("taskStatusCount", taskStatus.size());
        status.put("timestamp", new Date());

        // 添加内存信息
        Runtime runtime = Runtime.getRuntime();
        status.put("memoryTotal", runtime.totalMemory() / 1024 / 1024 + "MB");
        status.put("memoryFree", runtime.freeMemory() / 1024 / 1024 + "MB");
        status.put("memoryUsed", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + "MB");

        return ResponseEntity.ok(status);
    }

    /**
     * 获取历史对话
     */
    @GetMapping("/chat/history")
    public ResponseEntity<?> getChatHistory(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        List<AiConversation> conversations;

        if (sessionId != null && !sessionId.isEmpty()) {
            conversations = aiConversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtDesc(userId, sessionId);
            if (conversations.size() > limit) {
                conversations = conversations.subList(0, limit);
            }
        } else {
            conversations = aiConversationRepository
                    .findByUserIdOrderByCreatedAtDesc(userId);
            if (conversations.size() > limit) {
                conversations = conversations.subList(0, limit);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", conversations);

        return ResponseEntity.ok(response);
    }

    /**
     * 健康检查端点
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", new Date());
        status.put("service", "smart-campus-ai");
        return ResponseEntity.ok(status);
    }

    /**
     * 诊断端点（用于调试）
     */
    @PostMapping(value = "/chat/diagnose", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> diagnoseMultipart(
            @RequestParam(value = "question", required = false) String question,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "stream", required = false) String streamStr,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest rawRequest) {

        Map<String, Object> logMap = new HashMap<>();

        // 1. 记录接收到的原始参数
        logMap.put("收到参数 - question", question);
        logMap.put("收到参数 - sessionId", sessionId);
        logMap.put("收到参数 - streamStr", streamStr);
        logMap.put("收到参数 - file为空", file == null || file.isEmpty());
        logMap.put("收到参数 - authHeader存在", authHeader != null && authHeader.startsWith("Bearer "));

        // 2. 记录Spring无法解析的所有参数名
        Enumeration<String> paramNames = rawRequest.getParameterNames();
        List<String> springParamNames = Collections.list(paramNames);
        logMap.put("Spring解析的参数名列表", springParamNames);

        // 3. 检查请求内容类型
        logMap.put("请求Content-Type", rawRequest.getContentType());

        // 4. 直接返回诊断信息
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "诊断端点调用成功");
        response.put("data", logMap);

        log.info("诊断端点调用详情：{}", logMap);
        return ResponseEntity.ok(response);
    }

    /**
     * 调试端点
     */
    @PostMapping(value = "/chat/debug", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> debugMultipart(
            @RequestParam("question") String question,
            @RequestParam(value = "stream", required = false) String streamStr,
            HttpServletRequest rawRequest) {

        log.info("=== DEBUG 端点被调用 ===");
        log.info("问题参数: {}", question);
        log.info("stream参数: {}", streamStr);

        // 打印所有请求参数
        rawRequest.getParameterMap().forEach((key, values) -> {
            log.info("参数 {} = {}", key, String.join(",", values));
        });

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "调试成功");
        response.put("data", Map.of(
                "question", question,
                "stream", streamStr
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * 辅助方法：保存学习文件
     */
    private LearningFile saveLearningFile(MultipartFile file, String userId) throws Exception {
        LearningFile learningFile = new LearningFile();
        learningFile.setUserId(Long.parseLong(userId));
        learningFile.setOriginalName(file.getOriginalFilename());
        learningFile.setFileName(UUID.randomUUID() + "_" + file.getOriginalFilename());
        learningFile.setFileType(getFileExtension(file.getOriginalFilename()));
        learningFile.setFileSize(file.getSize());
        learningFile.setUploadTime(LocalDateTime.now());
        learningFile.setStatus("active");

        // 保存文件到服务器
        String filePath = "/opt/smart-campus/uploads/" + learningFile.getFileName();
        file.transferTo(new java.io.File(filePath));
        learningFile.setFilePath(filePath);

        return learningFileRepository.save(learningFile);
    }

    /**
     * 辅助方法：保存对话记录
     */
    private void saveConversation(Long userId, String sessionId,
                                  String question, String answer, Long fileId) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setSessionId(sessionId);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setFileId(fileId);
        conversation.setCreatedAt(LocalDateTime.now());

        // 生成简短标题
        String title = question.length() > 30 ?
                question.substring(0, 30) + "..." : question;
        conversation.setTitle(title);

        aiConversationRepository.save(conversation);
    }

    /**
     * 优化后的保存对话记录方法（带重试）
     */
    private void saveConversationWithRetry(String userId, String sessionId,
                                           String question, String answer, Long fileId) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                saveConversation(Long.parseLong(userId), sessionId, question, answer, fileId);
                log.info("对话记录保存成功，长度: {}", answer.length());
                return;
            } catch (Exception e) {
                retryCount++;
                log.warn("保存对话记录失败，重试 {}/{}，错误: {}",
                        retryCount, maxRetries, e.getMessage());

                if (retryCount >= maxRetries) {
                    log.error("保存对话记录最终失败", e);
                    // 尝试保存简化版本
                    try {
                        String shortAnswer = answer.length() > 5000 ?
                                answer.substring(0, 5000) + "..." : answer;
                        saveConversation(Long.parseLong(userId), sessionId, question, shortAnswer, fileId);
                        log.info("已保存简化版对话记录");
                    } catch (Exception ex) {
                        log.error("连简化版也保存失败", ex);
                    }
                } else {
                    try {
                        TimeUnit.SECONDS.sleep(retryCount); // 指数退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * 辅助方法：更新文件摘要
     */
    private void updateFileSummary(Long fileId, String aiAnswer) {
        if (aiAnswer == null || aiAnswer.isEmpty()) {
            return;
        }

        // 从AI回答中提取关键信息作为摘要
        String summary = aiAnswer.length() > 200 ?
                aiAnswer.substring(0, 200) + "..." : aiAnswer;

        learningFileRepository.updateSummary(fileId, summary);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    /**
     * 构建错误响应（返回ResponseEntity）
     */
    private ResponseEntity<Map<String, Object>> buildErrorResponse(int code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("data", null);

        HttpStatus status = switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(error);
    }

    /**
     * 统一异常处理
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        log.error("服务器异常", e);

        // 生产环境不返回详细错误
        String message = "服务器内部错误";
        if (e instanceof TimeoutException) {
            message = "请求超时，请稍后重试";
        }

        return ResponseEntity.status(500)
                .body(Map.of("code", 500, "message", message));
    }
}