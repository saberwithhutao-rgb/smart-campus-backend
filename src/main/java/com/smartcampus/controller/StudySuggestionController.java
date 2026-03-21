package com.smartcampus.controller;

import com.smartcampus.dto.ApiResponse;
import com.smartcampus.service.StudySuggestionService;
import com.smartcampus.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习建议控制器
 */
@RestController
@RequestMapping("/api/study")
public class StudySuggestionController {

    @Autowired
    private StudySuggestionService studySuggestionService;

    @Autowired
    private JwtUtil jwtUtil;

    // 任务缓存，存储任务ID和对应的CompletableFuture
    private final Map<String, CompletableFuture<ApiResponse>> taskCache = new ConcurrentHashMap<>();

    /**
     * 获取学习建议（同步）
     */
    @GetMapping("/suggestions")
    public ApiResponse getStudySuggestions(
            @RequestParam(value = "timeRange", defaultValue = "today") String timeRange,
            HttpServletRequest request) {

        System.out.println("开始获取学习建议，时间范围: " + timeRange);

        // 从请求头获取token并解析userId
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.error(401, "未授权访问");
        }

        String token = authHeader.substring(7);
        Long userIdLong = jwtUtil.getUserIdFromToken(token);
        Integer userId = userIdLong.intValue();

        // 直接调用Service
        return studySuggestionService.getStudySuggestions(userId, timeRange);
    }

    /**
     * 提交异步学习建议任务
     */
    @GetMapping("/suggestions/async")
    public ApiResponse submitAsyncSuggestionsTask(
            @RequestParam(value = "timeRange", defaultValue = "today") String timeRange,
            HttpServletRequest request) {

        System.out.println("提交异步学习建议任务，时间范围: " + timeRange);

        // 从请求头获取token并解析userId
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.error(401, "未授权访问");
        }

        String token = authHeader.substring(7);
        Long userIdLong = jwtUtil.getUserIdFromToken(token);
        Integer userId = userIdLong.intValue();

        // 生成唯一任务ID
        String taskId = UUID.randomUUID().toString();

        // 异步执行任务
        CompletableFuture<ApiResponse> future = studySuggestionService.getStudySuggestionsAsync(userId, timeRange);

        // 存储任务到缓存
        taskCache.put(taskId, future);

        // 任务完成后从缓存中移除
        future.thenRun(() -> {
            taskCache.remove(taskId);
            System.out.println("异步任务完成并从缓存中移除: " + taskId);
        });

        // 返回任务ID
        Map<String, Object> data = Map.of(
                "taskId", taskId,
                "message", "任务已提交，正在处理中"
        );
        return ApiResponse.success(data);
    }

    /**
     * 查询异步任务结果
     */
    @GetMapping("/suggestions/query")
    public ApiResponse querySuggestionsTask(@RequestParam(value = "taskId") String taskId) {
        System.out.println("查询异步任务结果，任务ID: " + taskId);

        CompletableFuture<ApiResponse> future = taskCache.get(taskId);

        if (future == null) {
            // 任务不存在或已完成
            return ApiResponse.error(404, "任务不存在或已完成").data(new ArrayList<>());
        }

        if (future.isDone()) {
            // 任务已完成，返回结果
            try {
                ApiResponse taskResult = future.get();
                System.out.println("任务已完成，返回结果");
                return taskResult;
            } catch (Exception e) {
                System.err.println("获取任务结果失败: " + e.getMessage());
                e.printStackTrace();
                return ApiResponse.error(500, "获取任务结果失败").data(new ArrayList<>());
            }
        } else {
            // 任务正在处理中
            return ApiResponse.error(202, "任务正在处理中，请稍后查询").data(new ArrayList<>());
        }
    }
}