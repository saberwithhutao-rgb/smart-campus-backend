package com.smartcampus.controller;

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

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiQaController {
    @Autowired
    private JwtUtil jwtUtil;

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
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        log.info("AI聊天接口被调用，问题: {}, sessionId: {}, stream: {}",
                question, sessionIdParam, streamParam);

        try {
            // 1. 验证认证头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("缺少或无效的认证头");
                return ResponseEntity.status(401)
                        .body(Map.of("code", 401, "message", "未授权"));
            }

            // 2. 解析token（简化版，跳过JWT验证）
            String token = authHeader.substring(7);
            Long userId = 1L; // 默认使用autouser

            // 3. 处理参数
            String sessionId = (sessionIdParam != null && !sessionIdParam.isEmpty())
                    ? sessionIdParam
                    : "sess_" + System.currentTimeMillis();

            boolean stream = "true".equalsIgnoreCase(streamParam) || "1".equals(streamParam);

            // 4. 简单响应（先确保接口能通）
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");

            Map<String, Object> data = new HashMap<>();
            data.put("answer", "已收到您的问题: " + question +
                    " (sessionId: " + sessionId + ", stream: " + stream + ")");
            data.put("sessionId", sessionId);

            response.put("data", data);

            log.info("AI接口响应成功");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("AI接口异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器错误: " + e.getMessage()));
        }
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
            @RequestParam(value = "stream", required = false) String streamStr, // 使用String接收
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
    public String handleChatPage() {
        // 重定向到首页，Vue Router会处理/ai/chat路由
        return "redirect:/";
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
                String aiAnswer = String.valueOf(qianWenService.askQuestion(enhancedQuestion,
                        Collections.emptyList(),
                        "qwen-max"));

                // 5. 保存对话记录
                saveConversation(userId, sessionId, question, aiAnswer, learningFile.getId());

                // 6. 更新文件摘要
                updateFileSummary(learningFile.getId(), aiAnswer);

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
     * 处理纯文本问题
     */
    private ResponseEntity<?> handleTextQuestion(String question, String userId,
                                                 String sessionId, Boolean stream,
                                                 HttpServletResponse response) {

        if (Boolean.TRUE.equals(stream)) {
            // 流式输出
            return handleStreamResponse(question, userId, sessionId, response);
        } else {
            // 普通响应
            String aiAnswer = String.valueOf(qianWenService.askQuestion(question, Collections.emptyList(), "qwen-max"));

            // 保存对话记录
            saveConversation(userId, sessionId, question, aiAnswer, null);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("code", 200);
            responseData.put("message", "success");
            responseData.put("data", Map.of(
                    "answer", aiAnswer,
                    "sessionId", sessionId
            ));

            return ResponseEntity.ok(responseData);
        }
    }

    /**
     * 流式响应处理
     */
    private ResponseEntity<?> handleStreamResponse(String question, String userId,
                                                   String sessionId, HttpServletResponse response) {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(30000L); // 30秒超时

        executorService.submit(() -> {
            try {
                String aiAnswer = String.valueOf(qianWenService.askQuestion(question, Collections.emptyList(), "qwen-max"));

                // 模拟流式输出（每50毫秒输出一个字）
                StringBuilder currentAnswer = new StringBuilder();
                for (char c : aiAnswer.toCharArray()) {
                    currentAnswer.append(c);
                    emitter.send(SseEmitter.event()
                            .data(currentAnswer.toString())
                            .name("message"));
                    Thread.sleep(50);
                }

                // 保存完整对话
                saveConversation(userId, sessionId, question, aiAnswer, null);

                emitter.send(SseEmitter.event()
                        .data("COMPLETE")
                        .name("complete"));
                emitter.complete();

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return ResponseEntity.ok(emitter);
    }

    /**
     * 文件解析状态查询 - 完全匹配文档
     * GET /ai/chat/task/{taskId}
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
            data.put("answer", status.substring(10)); // 提取答案
        } else if (status.startsWith("failed:")) {
            data.put("status", "failed");
            data.put("error", status.substring(7)); // 提取错误信息
        } else {
            data.put("status", "processing");
        }

        response.put("data", data);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取历史对话 - 匹配前端需求
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
            // 简化调用：不使用Pageable
            conversations = aiConversationRepository
                    .findByUserIdAndSessionIdOrderByCreatedAtDesc(userId, sessionId);
            if (conversations.size() > limit) {
                conversations = conversations.subList(0, limit);
            }
        } else {
            // 简化调用
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
     * 辅助方法：更新文件摘要
     */
    private void updateFileSummary(Long fileId, String aiAnswer) {
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