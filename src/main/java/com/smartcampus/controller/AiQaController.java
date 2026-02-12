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
import reactor.core.publisher.Flux;

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
        executorService = new ThreadPoolExecutor(
                5, 20, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
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
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/"))
                .build();
    }

    /**
     * 统一智能问答接口 - 根据stream参数选择模式
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object chatWithAi(
            @RequestParam("question") String question,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionIdParam,
            @RequestParam(value = "stream", defaultValue = "false") String streamParam,
            @RequestHeader(value = "Authorization") String authHeader) {

        log.info("AI聊天接口被调用，问题: {}, stream: {}", question, streamParam);

        try {
            Long userId = validateAndExtractUserId(authHeader);
            if (userId == null) {
                return ResponseEntity.status(401)
                        .body(Map.of("code", 401, "message", "未授权或Token无效"));
            }

            String sessionId = (sessionIdParam != null && !sessionIdParam.isEmpty())
                    ? sessionIdParam
                    : generateSessionId();

            boolean stream = "true".equalsIgnoreCase(streamParam) || "1".equals(streamParam);

            // ✅ 流式输出 - 直接返回通义千问原生流式
            if (stream) {
                // 流式不支持文件上传
                if (file != null && !file.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("code", 400, "message", "流式输出暂不支持文件上传"));
                }
                // ✅ 直接返回Flux，通义千问原生格式
                return chatStream(question, sessionId, authHeader);
            }
            // 非流式输出
            else {
                return handleNormalChat(question, file, userId, sessionId);
            }

        } catch (Exception e) {
            log.error("AI接口异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }


    /**
     * ✅ 真正的流式问答接口 - 直接返回通义千问原生流式格式
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未授权"));
        }

        String finalSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId
                : generateSessionId();

        log.info("✅ 通义千问原生流式开始，用户: {}, 会话: {}", userId, finalSessionId);

        return qianWenService.askQuestionStream(question, Collections.emptyList(), "qwen-max")
                .map(chunk -> "data: " + chunk + "\n\n")
                .doOnComplete(() -> {
                    log.info("流式输出完成，会话ID: {}", finalSessionId);
                })
                .doOnError(error -> {
                    log.error("流式输出错误", error);
                });
    }

    /**
     * 处理普通聊天响应（非流式）
     */
    private ResponseEntity<?> handleNormalChat(String question, MultipartFile file,
                                               Long userId, String sessionId) {
        // 有文件上传
        if (file != null && !file.isEmpty()) {
            return handleFileUpload(question, file, userId.toString(), sessionId);
        }

        // 纯文本问题
        log.info("非流式调用通义千问: {}", question);

        try {
            String aiAnswer = qianWenService.askQuestion(question,
                            Collections.emptyList(), "qwen-max")
                    .block(Duration.ofSeconds(90));

            if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                aiAnswer = "AI服务返回空响应，请稍后重试。";
            }

            // 异步保存对话记录
            String finalAnswer = aiAnswer;
            executorService.submit(() -> {
                saveConversationWithRetry(userId.toString(), sessionId, question, finalAnswer, null);
            });

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", Map.of(
                            "answer", aiAnswer,
                            "sessionId", sessionId
                    )
            ));

        } catch (Exception e) {
            log.error("调用AI服务失败", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "AI服务暂时不可用",
                    "data", Map.of(
                            "answer", "AI服务响应超时，请稍后重试。",
                            "sessionId", sessionId
                    )
            ));
        }
    }

    /**
     * 验证并提取用户ID
     */
    private Long validateAndExtractUserId(String authHeader) {
        log.info("=== 开始验证Token ===");
        log.info("authHeader: {}", authHeader);

        if (authHeader == null) {
            log.warn("authHeader为null");
            return null;
        }

        if (!authHeader.startsWith("Bearer ")) {
            log.warn("authHeader不以'Bearer '开头: {}", authHeader);
            return null;
        }

        try {
            String token = authHeader.substring(7);
            log.info("提取的token长度: {}", token.length());

            // 检查jwtUtil是否为空
            if (jwtUtil == null) {
                log.error("❌ jwtUtil为null，依赖注入失败！");
                return null;
            }

            log.info("调用jwtUtil.validateToken...");
            boolean isValid = jwtUtil.validateToken(token);
            log.info("Token验证结果: {}", isValid);

            if (!isValid) {
                log.warn("Token验证失败");
                return null;
            }

            Long userId = jwtUtil.getUserIdFromToken(token);
            log.info("提取的userId: {}", userId);

            return userId;

        } catch (Exception e) {
            log.error("❌ Token解析异常", e);
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
                        .block(Duration.ofSeconds(90));

                // ✅ 5. 保存对话记录 - 修改这里
                saveConversationToDb(Long.parseLong(userId), sessionId, question, aiAnswer, learningFile.getId());

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
     * 诊断端点，检查各组件状态
     */
    @PostMapping("/chat/diagnose")
    public ResponseEntity<?> diagnose(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> status = new HashMap<>();
        status.put("timestamp", new Date());
        status.put("service", "smart-campus-ai");

        try {
            // 1. 检查Token验证
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                status.put("tokenLength", token.length());
                status.put("tokenValid", jwtUtil.validateToken(token));

                if (jwtUtil.validateToken(token)) {
                    Long userId = jwtUtil.getUserIdFromToken(token);
                    status.put("userId", userId);
                    status.put("userExists", userId != null && userRepository.existsById(Math.toIntExact(userId)));
                }
            }

            // 2. 检查AI服务
            try {
                // 尝试简单调用AI服务
                String testResponse = qianWenService.askQuestion("测试",
                                Collections.emptyList(), "qwen-max")
                        .block(Duration.ofSeconds(90));
                status.put("aiService", "正常");
                status.put("aiResponseLength", testResponse != null ? testResponse.length() : 0);
            } catch (Exception e) {
                status.put("aiService", "异常: " + e.getMessage());
            }

            // 3. 检查数据库
            try {
                long userCount = userRepository.count();
                long conversationCount = aiConversationRepository.count();
                status.put("database", "正常");
                status.put("userCount", userCount);
                status.put("conversationCount", conversationCount);
            } catch (Exception e) {
                status.put("database", "异常: " + e.getMessage());
            }

            // 4. 检查线程池
            if (executorService instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor pool = (ThreadPoolExecutor) executorService;
                status.put("threadPool", Map.of(
                        "activeThreads", pool.getActiveCount(),
                        "queueSize", pool.getQueue().size(),
                        "poolSize", pool.getPoolSize()
                ));
            }

            status.put("code", 200);
            status.put("message", "诊断完成");

            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("诊断端点异常", e);
            status.put("code", 500);
            status.put("message", "诊断失败: " + e.getMessage());
            status.put("error", e.getClass().getName());

            return ResponseEntity.status(500).body(status);
        }
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
     * 优化后的保存对话记录方法（带重试）
     */
    private void saveConversationWithRetry(String userId, String sessionId,
                                           String question, String answer, Long fileId) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                saveConversationToDb(Long.parseLong(userId), sessionId, question, answer, fileId);
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
                        saveConversationToDb(Long.parseLong(userId), sessionId, question, shortAnswer, fileId);
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

    // 将数据库保存方法改名
    private void saveConversationToDb(Long userId, String sessionId,
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
     * 保存对话请求体
     */
    class SaveConversationRequest {
        private String sessionId;
        private String question;
        private String answer;

        // getters and setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
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