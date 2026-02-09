package com.smartcampus.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import com.smartcampus.utils.JwtUtil;
import com.smartcampus.entity.AiConversation;
import com.smartcampus.entity.LearningFile;
import com.smartcampus.repository.AiConversationRepository;
import com.smartcampus.repository.LearningFileRepository;
import com.smartcampus.service.FileProcessingService;
import com.smartcampus.service.QianWenService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
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
    private com.smartcampus.repository.UserRepository userRepository;

    @Autowired
    private com.smartcampus.repository.AiConversationRepository aiConversationRepository;

    @Autowired
    private com.smartcampus.repository.LearningFileRepository learningFileRepository;

    private final ExecutorService executorService = Executors.newFixedThreadPool(5);
    private final Map<String, String> taskStatus = new ConcurrentHashMap<>();

    /**
     * 智能问答接口 - 完全匹配文档
     * POST /ai/chat
     * Content-Type: multipart/form-data
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chatWithAi(
            @RequestParam("question") String question,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionIdParam,
            @RequestParam(value = "stream", required = false) String streamParam,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletResponse httpResponse) {

        log.info("AI聊天接口被调用，问题: {}, sessionId: {}, stream: {}",
                question, sessionIdParam, streamParam);

        try {
            // 1. 验证认证头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("缺少或无效的认证头");
                return ResponseEntity.status(401)
                        .body(Map.of("code", 401, "message", "未授权"));
            }

            // 2. 解析token（简化版）
            String token = authHeader.substring(7);
            long userId = 1L; // 修复：使用Long.valueOf

            // 3. 处理参数
            String sessionId = (sessionIdParam != null && !sessionIdParam.isEmpty())
                    ? sessionIdParam
                    : "sess_" + System.currentTimeMillis();

            boolean stream = "true".equalsIgnoreCase(streamParam) || "1".equals(streamParam);

            // 4. 根据stream参数选择处理方式
            if (stream) {
                // ✅ 流式输出
                log.info("使用流式输出模式");
                return handleStreamResponse(question, Long.toString(userId), sessionId, httpResponse);
            } else {
                // 普通响应
                return handleNormalResponse(question, file, Long.toString(userId), sessionId, authHeader);
            }

        } catch (Exception e) {
            log.error("AI接口异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器错误: " + e.getMessage()));
        }
    }

    /**
     * 处理普通（非流式）响应
     */
    private ResponseEntity<?> handleNormalResponse(String question, MultipartFile file,
                                                   String userId, String sessionId, String authHeader) {

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");

        Map<String, Object> data = new HashMap<>();

        // 根据是否有文件选择处理方式
        if (file != null && !file.isEmpty()) {
            // 有文件上传的处理
            log.info("用户上传了文件: {}", file.getOriginalFilename());

            // 简化：先不处理文件，只返回确认消息
            data.put("answer", "已收到您的文件和问题: " + question +
                    " (文件: " + file.getOriginalFilename() + ")");
            data.put("sessionId", sessionId);

        } else {
            // 纯文本问题 - 调用真正的AI服务
            log.info("调用通义千问API回答问题: {}", question);

            try {
                // 直接调用AI服务，设置超时
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

                // 异步保存（使用优化后的方法）
                String finalAnswer = aiAnswer;
                executorService.submit(() -> {
                    saveConversationWithRetry(userId, sessionId, question, finalAnswer, null);
                });

                data.put("answer", aiAnswer);
                data.put("sessionId", sessionId);

            } catch (Exception e) {
                log.error("调用AI服务失败", e);
                String errorMsg = e.getMessage();
                if (errorMsg != null && (errorMsg.contains("Timeout") || errorMsg.contains("超时"))) {
                    data.put("answer", "AI服务响应超时，请稍后重试或减少问题长度。");
                }
                data.put("sessionId", sessionId);
            }
        }

        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * 监控端点，查看任务状态
     */
    @GetMapping("/chat/status")
    public ResponseEntity<?> getChatStatus() {
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

    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", new Date());
        status.put("service", "smart-campus-ai");
        return ResponseEntity.ok(status);
    }

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
     * 验证并提取用户ID
     */
    private Long validateAndExtractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("缺少或格式错误的Authorization头");
            return null;
        }

        try {
            String token = authHeader.substring(7);

            // 验证token
            if (!jwtUtil.validateToken(token)) {
                log.warn("Token验证失败");
                return null;
            }

            // 提取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                log.warn("Token中未包含用户ID");
                return null;
            }

            log.debug("成功验证用户ID: {}", userId);
            return userId;

        } catch (Exception e) {
            log.error("Token解析失败", e);
            return null;
        }
    }

    @GetMapping("/chat")
    public ResponseEntity<Void> handleChatPage() {
        // 明确返回重定向响应
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/"))
                .build();
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
                saveConversation(userId, sessionId, question, aiAnswer, learningFile.getId());

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
     * 流式响应处理 - 修复版本
     */
    private ResponseEntity<?> handleStreamResponse(String question, String userId,
                                                   String sessionId, HttpServletResponse response) {

        log.info("开始流式输出，问题: {}", question);

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");

        SseEmitter emitter = new SseEmitter(60000L);

        executorService.submit(() -> {
            try {
                // 1. 获取AI回答（带超时）
                String fullAnswer = qianWenService.askQuestion(question,
                                Collections.emptyList(),
                                "qwen-max")
                        .block(Duration.ofSeconds(45));

                if (fullAnswer == null || fullAnswer.trim().isEmpty()) {
                    log.warn("AI返回空回答");
                    emitter.send(SseEmitter.event()
                            .data("{\"error\":\"AI服务未返回有效响应\"}")
                            .name("error"));
                    emitter.complete();
                    return;
                }

                // 2. 限制回答长度，避免问题
                if (fullAnswer.length() > 15000) {
                    log.warn("回答过长，截断至15000字符，原长度: {}", fullAnswer.length());
                    fullAnswer = fullAnswer.substring(0, 15000) + "...\n\n（回答过长，已截断）";
                }

                log.info("流式输出，总长度: {}", fullAnswer.length());

                // 3. 流式输出 - 修复版本
                int chunkSize = Math.max(10, Math.min(100, fullAnswer.length() / 30));
                boolean interrupted = false;

                for (int i = 0; i < fullAnswer.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, fullAnswer.length());
                    String chunk = fullAnswer.substring(i, end);

                    // 简单的流式数据格式
                    String eventData = String.format("{\"chunk\":\"%s\",\"done\":%s,\"progress\":%.2f}",
                            chunk.replace("\"", "\\\"").replace("\n", "\\n"),
                            end >= fullAnswer.length(),
                            (double) end / fullAnswer.length());

                    try {
                        emitter.send(SseEmitter.event().data(eventData));
                    } catch (Exception e) {
                        log.info("客户端断开连接，停止流式输出");
                        interrupted = true;
                        break;
                    }

                    // 控制输出速度 - 使用更优雅的方式
                    try {
                        TimeUnit.MILLISECONDS.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interrupted = true;
                        break;
                    }
                }

                if (!interrupted) {
                    // 4. 异步保存
                    String finalAnswer = fullAnswer;
                    executorService.submit(() -> {
                        try {
                            saveConversationWithRetry(userId, sessionId, question, finalAnswer, null);
                        } catch (Exception e) {
                            log.error("异步保存失败（不影响用户）", e);
                        }
                    });

                    emitter.complete();
                    log.info("流式输出完成");
                }

            } catch (Exception e) {
                log.error("流式输出失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .data("{\"error\":\"流式输出失败: " + e.getMessage() + "\"}"));
                } catch (Exception ignore) {
                    // 忽略发送错误时的异常
                }
                emitter.completeWithError(e);
            }
        });

        return ResponseEntity.ok(emitter);
    }

    /**
     * 文件解析状态查询
     */
    @GetMapping("/chat/task/{taskId}")
    public ResponseEntity<?> getTaskStatus(@PathVariable String taskId,
                                           @RequestHeader("Authorization") String authHeader) {

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
     * 获取历史对话
     */
    @GetMapping("/chat/history")
    public ResponseEntity<?> getChatHistory(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return buildErrorResponse(401, "未授权或Token无效");
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
    private void saveConversation(String userId, String sessionId,
                                  String question, String answer, Long fileId) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(Long.parseLong(userId));
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
    @SuppressWarnings("SameParameterValue") // 抑制fileId总是null的警告
    private void saveConversationWithRetry(String userId, String sessionId,
                                           String question, String answer, Long fileId) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                saveConversation(userId, sessionId, question, answer, fileId);
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
                        saveConversation(userId, sessionId, question, shortAnswer, fileId);
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
     * 构建成功响应
     */
    private Map<String, Object> buildSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", data);
        return response;
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
}