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

import java.io.IOException;
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

            // ✅ 无论是否有文件，都支持流式
            if (stream) {
                // 流式模式 - 即使有文件也返回 SseEmitter
                return handleStreamingChat(question, file, sessionId, userId, authHeader);
            } else {
                // 非流式模式
                return handleNormalChat(question, file, userId, sessionId);
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
                                           String sessionId, Long userId,
                                           String authHeader) {

        SseEmitter emitter = new SseEmitter(120000L); // 2分钟超时

        // 异步处理
        executorService.submit(() -> {
            try {
                String enhancedQuestion = question;

                // 如果有文件，先处理文件
                if (file != null && !file.isEmpty()) {
                    // 1. 保存文件
                    LearningFile learningFile = saveLearningFile(file, userId.toString());

                    // 2. 提取文件内容
                    String fileContent = fileProcessingService.extractTextFromFile(file);

                    // 3. 增强问题（把文件内容作为上下文）
                    enhancedQuestion = question + "\n\n参考文件内容：\n" +
                            fileContent.substring(0, Math.min(2000, fileContent.length()));

                    // 4. 记录文件ID
                    // 可以在后续保存对话时使用
                }

                // 调用通义千问流式API
                qianWenService.askQuestionStream(enhancedQuestion, Collections.emptyList(), "qwen-max")
                        .doOnNext(chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                log.error("发送SSE数据失败", e);
                                throw new RuntimeException(e);
                            }
                        })
                        .doOnComplete(() -> {
                            // 流式完成后保存对话记录
                            // 注意：这里需要累积完整的回答，但通义千问的流式返回的是完整chunk
                            // 实际使用时可能需要累积完整文本
                            log.info("流式完成，会话ID: {}", sessionId);
                            emitter.complete();
                        })
                        .doOnError(error -> {
                            log.error("流式错误", error);
                            emitter.completeWithError(error);
                        })
                        .subscribe();

            } catch (Exception e) {
                log.error("处理流式聊天失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * ✅ 真正的流式问答接口 - 使用 SseEmitter 实现真正的流式输出
     * POST /ai/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestHeader("Authorization") String authHeader) {

        // 验证用户
        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未授权"));
            return emitter;
        }

        String finalSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId
                : generateSessionId();

        log.info("✅ SSE流式开始，用户: {}, 会话: {}", userId, finalSessionId);

        // 创建SseEmitter，设置超时时间2分钟
        SseEmitter emitter = new SseEmitter(120000L);

        // 设置完成回调
        emitter.onCompletion(() -> {
            log.info("SSE连接完成，会话ID: {}", finalSessionId);
        });

        // 设置超时回调
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时，会话ID: {}", finalSessionId);
            emitter.complete();
        });

        // 设置错误回调
        emitter.onError((ex) -> {
            log.error("SSE连接错误，会话ID: {}", finalSessionId, ex);
            emitter.completeWithError(ex);
        });

        // 🟢🟢🟢 订阅通义千问流式响应，实时转发 🟢🟢🟢
        qianWenService.askQuestionStream(question, Collections.emptyList(), "qwen-max")
                .doOnNext(chunk -> {
                    try {
                        // 通义千问返回的chunk已经是完整的SSE格式: data: {...}\n\n
                        // 直接发送给前端，不做任何包装
                        emitter.send(chunk);
                        log.debug("发送chunk: {}", chunk.substring(0, Math.min(50, chunk.length())));
                    } catch (IOException e) {
                        log.error("发送SSE数据失败", e);
                        throw new RuntimeException("发送失败", e);
                    }
                })
                .doOnComplete(() -> {
                    log.info("通义千问流式完成，会话ID: {}", finalSessionId);
                    emitter.complete();
                })
                .doOnError(error -> {
                    log.error("通义千问流式错误", error);
                    emitter.completeWithError(error);
                })
                .subscribe(); // 必须订阅

        return emitter;
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

            // 判断是否是会话的第一条消息
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
     * ================== 新增：历史对话相关接口 ==================
     */

    /**
     * 获取用户的会话列表（每个会话只返回一条记录）
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<?> getConversationSessions(
            @RequestHeader("Authorization") String authHeader) {

        Long userId = validateAndExtractUserId(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "未授权或Token无效"));
        }

        try {
            // 获取每个会话的第一条记录（用于标题）和最新记录（用于预览）
            List<Object[]> results = aiConversationRepository.findSessionSummaries(userId);

            List<Map<String, Object>> sessions = new ArrayList<>();

            for (Object[] row : results) {
                Map<String, Object> session = new HashMap<>();
                session.put("sessionId", row[0]);                     // session_id
                session.put("title", row[1] != null ? row[1] : "新对话");  // title
                session.put("preview", row[2]);                       // 最新的一条问题作为预览
                session.put("createTime", row[3]);                    // 第一条记录的创建时间
                session.put("messageCount", ((Number) row[4]).intValue()); // 消息数量

                // 如果有文件关联，查询文件信息
                if (row[5] != null) {
                    Long fileId = ((Number) row[5]).longValue();
                    Optional<LearningFile> fileOpt = learningFileRepository.findById(fileId);
                    fileOpt.ifPresent(file -> {
                        session.put("fileId", fileId);
                        session.put("fileName", file.getOriginalName());
                        session.put("fileType", file.getFileType());
                    });
                }

                sessions.add(session);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("data", sessions);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "获取会话列表失败"));
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
            // 验证该会话属于当前用户
            if (!aiConversationRepository.existsBySessionIdAndUserId(sessionId, userId)) {
                return ResponseEntity.status(403)
                        .body(Map.of("code", 403, "message", "无权访问该会话"));
            }

            List<AiConversation> conversations = aiConversationRepository
                    .findBySessionIdOrderByCreatedAtAsc(sessionId);

            List<Map<String, Object>> history = new ArrayList<>();

            for (AiConversation conv : conversations) {
                Map<String, Object> item = new HashMap<>();
                item.put("question", conv.getQuestion());
                item.put("answer", conv.getAnswer());
                item.put("createTime", conv.getCreatedAt());
                item.put("questionType", conv.getQuestionType() != null ? conv.getQuestionType() : "text");
                item.put("rating", conv.getRating() != null ? conv.getRating() : 0);
                item.put("tokenUsage", conv.getTokenUsage());

                // 如果有文件关联，查询文件信息
                if (conv.getFileId() != null) {
                    Optional<LearningFile> fileOpt = learningFileRepository.findById(conv.getFileId());
                    fileOpt.ifPresent(file -> {
                        item.put("fileId", file.getId());
                        item.put("fileName", file.getOriginalName());
                        item.put("fileType", file.getFileType());
                    });
                }

                history.add(item);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("data", history);

            return ResponseEntity.ok(response);

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
            if (!conversations.get(0).getUserId().equals(userId)) {
                return ResponseEntity.status(403)
                        .body(Map.of("code", 403, "message", "无权修改该会话"));
            }

            // 更新第一条记录的title
            AiConversation firstConv = conversations.get(0);
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
     * 获取历史对话（原有接口，保持兼容）
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

        conversation.setRating((short) 0);  // 或者 conversation.setRating(Short.valueOf("0"));

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
}