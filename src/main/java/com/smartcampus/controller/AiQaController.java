package com.smartcampus.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcampus.dto.GenerateReviewAdviceRequest;
import com.smartcampus.entity.AiConversation;
import com.smartcampus.entity.LearningFile;
import com.smartcampus.exception.BusinessException;
import com.smartcampus.repository.AiConversationRepository;
import com.smartcampus.repository.LearningFileRepository;
import com.smartcampus.repository.UserRepository;
import com.smartcampus.service.FileProcessingService;
import com.smartcampus.service.QianWenService;
import com.smartcampus.service.ReviewAdviceService;
import com.smartcampus.service.ReviewSuggestionService;
import com.smartcampus.service.StudyPlanDetailService;
import com.smartcampus.utils.JwtUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiQaController {
    private final StudyPlanDetailService studyPlanDetailService;
    private final ReviewSuggestionService reviewSuggestionService;

    @Autowired
    private ReviewAdviceService reviewAdviceService;

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

    public AiQaController(QianWenService qianWenService, JwtUtil jwtUtil, StudyPlanDetailService studyPlanDetailService, ReviewSuggestionService reviewSuggestionService) {
        this.qianWenService = qianWenService;
        this.jwtUtil = jwtUtil;
        this.studyPlanDetailService = studyPlanDetailService; // 注入
        this.reviewSuggestionService = reviewSuggestionService;
    }

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
    /**
     * 统一智能问答接口 - 支持流式/非流式，支持文件上传
     */
    @PostMapping(value = "/chat",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object chatWithAi(
            @RequestParam("question") String question,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionIdParam,
            @RequestParam(value = "stream", defaultValue = "false") String streamParam,
            @RequestHeader(value = "Authorization") String authHeader) {

        log.info("🚀 AI聊天接口被调用 ==========");
        log.info("📝 问题: {}", question);
        log.info("📎 是否有文件: {}", file != null && !file.isEmpty());
        log.info("🔑 sessionId: {}", sessionIdParam);
        log.info("🌊 stream参数: {}", streamParam);

        try {
            Long userId = validateAndExtractUserId(authHeader);
            if (userId == null) {
                return ResponseEntity.status(401)
                        .body(Map.of("code", 401, "message", "未授权或Token无效"));
            }

            String sessionId = (sessionIdParam != null && !sessionIdParam.isEmpty())
                    ? sessionIdParam
                    : generateSessionId();

            boolean stream = "true".equalsIgnoreCase(streamParam);

            if (stream) {
                // 流式模式 - 支持文件上传
                return handleStreamingChat(question, file, sessionId, userId);
            } else {
                // 非流式模式 - 不支持文件上传（文件上传必须用流式）
                if (file != null && !file.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("code", 400, "message", "文件上传必须使用流式模式（stream=true）"));
                }
                return handleNormalChat(question, userId, sessionId);
            }

        } catch (Exception e) {
            log.error("AI接口异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }


    /**
     * 处理流式聊天（支持文件上传）
     */
    private SseEmitter handleStreamingChat(String question, MultipartFile file,
                                           String sessionId, Long userId) {

        SseEmitter emitter = new SseEmitter(120000L);

        // 设置超时回调
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时，会话ID: {}", sessionId);
            emitter.complete();
        });

        // 设置错误回调
        emitter.onError((ex) -> {
            log.error("SSE连接错误，会话ID: {}", sessionId, ex);
            try {
                Map<String, Object> errorMsg = Map.of(
                        "error", "处理失败",
                        "message", ex.getMessage()
                );
                emitter.send(errorMsg);
            } catch (IOException e) {
                log.error("发送错误消息失败", e);
            }
            emitter.complete();
        });

        // ===== 方案一：先在主线程中保存文件 =====
        String savedFilePath = null;
        Long fileId = null;

        if (file != null && !file.isEmpty()) {
            try {
                // 1. 在主线程中保存文件到持久化目录
                LearningFile learningFile = saveLearningFile(file, userId.toString());
                fileId = learningFile.getId();
                savedFilePath = learningFile.getFilePath(); // 获取保存后的文件路径
                log.info("文件已保存到: {}", savedFilePath);
            } catch (Exception e) {
                log.error("保存文件失败", e);
                try {
                    Map<String, Object> errorMsg = Map.of(
                            "error", "文件保存失败",
                            "message", e.getMessage()
                    );
                    emitter.send(errorMsg);
                } catch (IOException ex) {
                    log.error("发送错误消息失败", ex);
                }
                emitter.complete();
                return emitter;
            }
        }

        // 创建 final 副本，用于 lambda 表达式
        final Long finalFileId = fileId;
        final String finalSavedFilePath = savedFilePath;
        final boolean isFirstMessage = aiConversationRepository.countByUserIdAndSessionId(userId, sessionId) == 0;
        final String finalSessionId = sessionId;
        final Long finalUserId = userId;
        final String finalQuestion = question;

        // 2. 异步线程中处理 AI 请求
        executorService.submit(() -> {
            try {
                String enhancedQuestion = question;

                // 如果有文件，从保存的文件路径读取内容
                if (finalSavedFilePath != null) {
                    // 调用 FileProcessingService 的新方法，从文件路径读取
                    String fileContent = fileProcessingService.extractTextFromFileByPath(finalSavedFilePath);

                    // 限制文件内容长度，避免提示词过长
                    if (fileContent.length() > 2000) {
                        fileContent = fileContent.substring(0, 2000) + "...\n[文件内容过长，已截断]";
                    }

                    enhancedQuestion = question + "\n\n参考文件内容：\n" + fileContent;
                }

                // 用于累积纯文本（保存到数据库用）
                StringBuilder fullAnswerText = new StringBuilder();

                // 调用通义千问流式API
                qianWenService.askQuestionStream(enhancedQuestion, Collections.emptyList(), "qwen-max")
                        .doOnNext(chunk -> {
                            try {
                                log.debug("收到 chunk: {}", chunk);

                                // 从chunk中提取纯文本内容并累积（用于保存到数据库）
                                String textChunk = extractTextFromChunk(chunk);
                                if (textChunk != null && !textChunk.isEmpty()) {
                                    fullAnswerText.append(textChunk);
                                }

                                // 原样发送给前端
                                emitter.send(chunk);

                            } catch (IOException e) {
                                log.error("发送SSE数据失败", e);
                                throw new RuntimeException(e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                log.info("流式完成，累积的纯文本长度: {}", fullAnswerText.length());

                                // 保存对话记录到数据库
                                saveConversationToDb(finalUserId, finalSessionId, finalQuestion,
                                        fullAnswerText.toString(), finalFileId, isFirstMessage);

                                log.info("流式完成，会话ID: {}, 回答长度: {}", finalSessionId, fullAnswerText.length());
                                emitter.complete();

                            } catch (Exception e) {
                                log.error("保存对话记录失败", e);
                                emitter.complete();
                            }
                        })
                        .doOnError(error -> {
                            log.error("流式处理错误: {}", error.getMessage());
                            try {
                                Map<String, Object> errorResponse = Map.of(
                                        "error", "AI处理失败",
                                        "message", error.getMessage()
                                );
                                emitter.send(errorResponse);
                            } catch (IOException e) {
                                log.error("发送错误消息失败", e);
                            }
                            emitter.complete();
                        })
                        .subscribe();

            } catch (Exception e) {
                log.error("处理流式聊天失败", e);
                try {
                    Map<String, Object> errorResponse = Map.of(
                            "error", "处理失败",
                            "message", e.getMessage()
                    );
                    emitter.send(errorResponse);
                } catch (IOException ex) {
                    log.error("发送错误消息失败", ex);
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 从chunk中提取纯文本内容
     */
    private String extractTextFromChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }

        try {
            // 情况1：如果是标准SSE格式（带data:前缀）
            if (chunk.startsWith("data: ")) {
                String jsonStr = chunk.substring(6).trim();

                // 如果是结束标记，返回空字符串
                if (jsonStr.equals("[DONE]")) {
                    return "";
                }

                // 解析JSON
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(jsonStr);

                // 提取content字段
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode delta = choices.get(0).path("delta");
                    if (delta.has("content")) {
                        return delta.path("content").asText();
                    }
                }
            }

            // 情况2：如果是纯JSON（没有data:前缀）
            else if (chunk.trim().startsWith("{")) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(chunk);

                // 提取content字段
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode delta = choices.get(0).path("delta");
                    if (delta.has("content")) {
                        return delta.path("content").asText();
                    }
                }
            }

            // 情况3：如果是纯文本，直接返回
            else {
                return chunk;
            }

        } catch (Exception e) {
            log.debug("解析chunk失败: {}, 错误: {}", chunk, e.getMessage());
            // 解析失败时，假设是纯文本，直接返回
            return chunk;
        }

        return "";
    }

    /**
     * 处理非流式聊天（纯文本）
     */
    private ResponseEntity<?> handleNormalChat(String question, Long userId, String sessionId) {
        log.info("非流式调用通义千问: {}", question);

        try {
            String aiAnswer = qianWenService.askQuestion(question,
                            Collections.emptyList(), "qwen-max")
                    .block(Duration.ofSeconds(90));

            if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
                aiAnswer = "AI服务返回空响应，请稍后重试。";
            }

            boolean isFirstMessage = aiConversationRepository.countByUserIdAndSessionId(userId, sessionId) == 0;

            // 异步保存对话记录
            String finalAnswer = aiAnswer;
            executorService.submit(() -> {
                saveConversationToDb(userId, sessionId, question, finalAnswer, null, isFirstMessage);
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

                // 5. 判断是否是会话的第一条消息
                Long userIdLong = Long.parseLong(userId);
                boolean isFirstMessage = aiConversationRepository.countByUserIdAndSessionId(userIdLong, sessionId) == 0;

                // 6. 保存对话记录
                saveConversationToDb(userIdLong, sessionId, question, aiAnswer, learningFile.getId(), isFirstMessage);

                // 7. 更新文件摘要
                if (aiAnswer != null) {
                    updateFileSummary(learningFile.getId(), aiAnswer);
                }

                // 8. 更新任务状态
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
        if (executorService instanceof ThreadPoolExecutor pool) {
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
     * 获取用户的会话列表（每个会话只返回一条记录）
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<?> getConversationSessions(
            @RequestHeader("Authorization") String authHeader) {

        log.info("🔥 getConversationSessions 开始执行");

        Long userId = validateAndExtractUserId(authHeader);

        if (userId == null) {
            log.warn("userId 为 null");
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            List<Object[]> results = aiConversationRepository.findSessionSummaries(userId);

            // 打印第一条数据看看
            if (!results.isEmpty()) {
                Object[] first = results.getFirst();
                log.info("第一条数据: sessionId={}, title={}, preview={}, createTime={}, count={}, fileId={}",
                        first[0], first[1], first[2], first[3], first[4], first[5]);
            }

            List<Map<String, Object>> sessions = new ArrayList<>();

            for (Object[] row : results) {
                try {
                    Map<String, Object> session = new HashMap<>();
                    session.put("sessionId", row[0]);
                    session.put("title", row[1] != null ? row[1] : "新对话");
                    session.put("preview", row[2]);
                    session.put("createTime", row[3]);
                    session.put("messageCount", ((Number) row[4]).intValue());

                    // 如果有文件关联，查询文件信息
                    if (row[5] != null) {
                        try {
                            Long fileId = ((Number) row[5]).longValue();
                            Optional<LearningFile> fileOpt = learningFileRepository.findById(fileId);
                            fileOpt.ifPresent(file -> {
                                session.put("fileId", fileId);
                                session.put("fileName", file.getOriginalName());
                                session.put("fileType", file.getFileType());
                            });
                        } catch (Exception e) {
                            log.error("查询文件信息失败, fileId={}", row[5], e);
                        }
                    }

                    sessions.add(session);
                } catch (Exception e) {
                    log.error("处理单条会话数据失败", e);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("data", sessions);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Connection", "close");

            return new ResponseEntity<>(response, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("获取会话列表失败", e);  // 打印完整堆栈
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    /**
     * 获取某个会话的完整对话记录
     */
    @GetMapping("/chat/history/{sessionId}")
    public ResponseEntity<?> getSessionHistory(
            @PathVariable String sessionId,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            log.info("========== getSessionHistory 开始 ==========");
            log.info("请求参数 - sessionId: {}, userId: {}", sessionId, userId);

            // 查询数据库
            List<AiConversation> conversations = aiConversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);

            // 如果没有找到记录，可以提前返回
            if (conversations.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "code", 200,
                        "message", "success",
                        "data", Collections.emptyList()
                ));
            }

            // 转换为前端需要的格式
            List<Map<String, Object>> history = new ArrayList<>();
            for (AiConversation conv : conversations) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", conv.getId());
                item.put("userId", conv.getUserId());
                item.put("title", conv.getTitle());
                item.put("sessionId", conv.getSessionId());
                item.put("question", conv.getQuestion());
                item.put("answer", conv.getAnswer());
                item.put("fileId", conv.getFileId());
                item.put("questionType", conv.getQuestionType());
                item.put("tokenUsage", conv.getTokenUsage());
                item.put("createdAt", conv.getCreatedAt());
                item.put("rating", conv.getRating());
                history.add(item);
            }

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", history
            ));

        } catch (Exception e) {
            log.error("获取会话历史失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "获取会话历史失败"));
        }
    }

    /**
     * 删除整个会话
     */
    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<?> deleteSession(
            @PathVariable String sessionId,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            int deletedCount = aiConversationRepository.deleteBySessionIdAndUserId(sessionId, userId);

            if (deletedCount == 0) {
                return ResponseEntity.status(404)
                        .body(Map.of("code", 404, "message", "会话不存在或无权删除"));
            }

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "删除成功",
                    "data", Map.of("deletedCount", deletedCount)
            ));

        } catch (Exception e) {
            log.error("删除会话失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "删除会话失败"));
        }
    }

    /**
     * 重命名会话（更新会话的第一条记录的title）
     */
    @PutMapping("/chat/session/{sessionId}")
    public ResponseEntity<?> renameSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body,
            @RequestHeader("Authorization") String authHeader) {

        String newTitle = body.get("title");
        if (newTitle == null || newTitle.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "标题不能为空"));
        }

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            // 获取该会话的第一条记录（作为会话标题）
            List<AiConversation> conversations = aiConversationRepository
                    .findBySessionIdOrderByCreatedAtAsc(sessionId);

            if (conversations.isEmpty()) {
                return ResponseEntity.status(404)
                        .body(Map.of("code", 404, "message", "会话不存在"));
            }

            // 验证所有权
            if (!conversations.getFirst().getUserId().equals(userId)) {
                return ResponseEntity.status(403)
                        .body(Map.of("code", 403, "message", "无权修改该会话"));
            }

            // 更新第一条记录的title
            AiConversation firstConv = conversations.getFirst();
            firstConv.setTitle(newTitle.trim());
            aiConversationRepository.save(firstConv);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "重命名成功"
            ));

        } catch (Exception e) {
            log.error("重命名会话失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "重命名会话失败"));
        }
    }

    /**
     * 评价回答
     */
    @PostMapping("/chat/rate/{conversationId}")
    public ResponseEntity<?> rateConversation(
            @PathVariable Long conversationId,
            @RequestBody Map<String, Integer> body,
            @RequestHeader("Authorization") String authHeader) {

        Integer rating = body.get("rating");
        if (rating == null || (rating != -1 && rating != 0 && rating != 1)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "评分必须为-1、0或1"));
        }

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            Optional<AiConversation> convOpt = aiConversationRepository.findById(conversationId);
            if (convOpt.isEmpty()) {
                return ResponseEntity.status(404)
                        .body(Map.of("code", 404, "message", "对话记录不存在"));
            }

            AiConversation conv = convOpt.get();
            // 验证所有权
            if (!conv.getUserId().equals(userId)) {
                return ResponseEntity.status(403)
                        .body(Map.of("code", 403, "message", "无权评价该对话"));
            }

            conv.setRating(rating.shortValue());
            aiConversationRepository.save(conv);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "评价成功"
            ));

        } catch (Exception e) {
            log.error("评价失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "评价失败"));
        }
    }

    /**
     * 获取用户的对话统计信息
     */
    @GetMapping("/chat/stats")
    public ResponseEntity<?> getChatStats(
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            // 总对话次数
            long totalCount = aiConversationRepository.countByUserId(userId);

            // 总token消耗
            Integer totalToken = aiConversationRepository.sumTokenUsageByUserId(userId);

            // 会话数量
            long sessionCount = aiConversationRepository.countDistinctSessionsByUserId(userId);

            // 评分统计
            Object[] ratingStats = aiConversationRepository.getRatingStatsByUserId(userId);

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalConversations", totalCount);
            stats.put("totalTokens", totalToken != null ? totalToken : 0);
            stats.put("totalSessions", sessionCount);

            if (ratingStats != null && ratingStats.length >= 3) {
                stats.put("positiveRatings", ((Number) ratingStats[0]).intValue());  // 满意
                stats.put("negativeRatings", ((Number) ratingStats[1]).intValue()); // 不满意
                stats.put("unrated", ((Number) ratingStats[2]).intValue());         // 未评价
            }

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", stats
            ));

        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "获取统计信息失败"));
        }
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
            if (executorService instanceof ThreadPoolExecutor pool) {
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
     * 优化后的保存对话记录方法（带重试）- 保留但改为调用新方法
     */
    private void saveConversationWithRetry(String userId, String sessionId,
                                           String question, String answer, Long fileId) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                Long userIdLong = Long.parseLong(userId);
                boolean isFirstMessage = aiConversationRepository.countByUserIdAndSessionId(userIdLong, sessionId) == 0;
                saveConversationToDb(userIdLong, sessionId, question, answer, fileId, isFirstMessage);
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
                        Long userIdLong = Long.parseLong(userId);
                        String shortAnswer = answer.length() > 5000 ?
                                answer.substring(0, 5000) + "..." : answer;
                        boolean isFirstMessage = aiConversationRepository.countByUserIdAndSessionId(userIdLong, sessionId) == 0;
                        saveConversationToDb(userIdLong, sessionId, question, shortAnswer, fileId, isFirstMessage);
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
     * 保存对话记录到数据库 - 改进版，支持判断是否第一条消息
     */
    private void saveConversationToDb(Long userId, String sessionId,
                                      String question, String answer, Long fileId, boolean isFirstMessage) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setSessionId(sessionId);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setFileId(fileId);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setQuestionType("text");

        conversation.setRating((short) 0);

        // 如果是会话的第一条消息，生成标题（取问题前30个字符）
        if (isFirstMessage) {
            String title = question.length() > 30 ?
                    question.substring(0, 30) + "..." : question;
            conversation.setTitle(title);
        }
        // 如果不是第一条，title保持null

        // 估算token使用量（简单估算：中文字符数 * 1.5 + 英文字符数 * 1.3）
        int estimatedTokens = (int)(question.length() * 1.5 + answer.length() * 1.3);
        conversation.setTokenUsage(estimatedTokens);

        aiConversationRepository.save(conversation);
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

    @PostMapping("/plan-detail")
    public ResponseEntity<?> generatePlanDetail(@RequestBody Map<String, String> requestData,
                                                @RequestHeader("Authorization") String authHeader) {

        log.info("🚀 接收生成学习计划请求: {}", requestData);

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        String title = requestData.get("title");
        String studyPlanIdStr = requestData.get("studyPlanId");
        String subject = requestData.get("subject");
        String duration = requestData.get("duration");
        String level = requestData.get("level");

        if (title == null || studyPlanIdStr == null || duration == null || level == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", "缺少必要参数: title, studyPlanId, subject, duration, level"));
        }

        try {
            Long studyPlanId = Long.parseLong(studyPlanIdStr);

            Map<String, Object> serviceResult = studyPlanDetailService.createPlanDetailForUser(
                    title, studyPlanId, subject, duration, level
            );

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", serviceResult
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", "studyPlanId 格式错误"));
        } catch (Exception e) {
            log.error("生成学习计划失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "生成学习计划失败: " + e.getMessage()));
        }
    }

    /**
     * AI生成复习建议
     */
    @PostMapping("/review/advice")
    public ResponseEntity<?> generateReviewAdvice(
            @RequestBody GenerateReviewAdviceRequest request,
            @RequestHeader("Authorization") String authHeader) {

        log.info("🚀 接收生成复习建议请求: {}", request);

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            String advice = reviewAdviceService.generateReviewAdvice(
                    userId,
                    request.getTaskId(),
                    request.getTitle(),
                    request.getReviewStage()
            );

            reviewSuggestionService.createSuggestion(
                    userId.intValue(),
                    request.getTaskId().intValue(),
                    advice
            );

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", advice
            ));

        } catch (BusinessException e) {
            return ResponseEntity.status(e.getCode())
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (Exception e) {
            log.error("生成复习建议失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "生成复习建议失败: " + e.getMessage()));
        }
    }
}