package com.smartcampus.controller;

import com.smartcampus.dto.ApiResponse;
import com.smartcampus.entity.StudyTask;
import com.smartcampus.service.StudyTaskService;
import com.smartcampus.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/study/tasks")
@RequiredArgsConstructor
public class StudyTaskController {

    private final StudyTaskService studyTaskService;
    private final JwtUtil jwtUtil;

    /**
     * 获取待复习任务列表
     */
    @GetMapping("/pending")
    public ApiResponse<List<StudyTask>> getPendingTasks(
            @RequestHeader("Authorization") String authHeader) {

        Integer userId = extractUserId(authHeader);
        List<StudyTask> tasks = studyTaskService.getPendingTasks(userId);

        return ApiResponse.success(tasks);
    }

    /**
     * 获取今天的复习任务
     */
    @GetMapping("/today")
    public ApiResponse<List<StudyTask>> getTodayTasks(
            @RequestHeader("Authorization") String authHeader) {

        Integer userId = extractUserId(authHeader);
        List<StudyTask> tasks = studyTaskService.getTodayTasks(userId);

        return ApiResponse.success(tasks);
    }

    /**
     * 获取逾期任务
     */
    @GetMapping("/overdue")
    public ApiResponse<List<StudyTask>> getOverdueTasks(
            @RequestHeader("Authorization") String authHeader) {

        Integer userId = extractUserId(authHeader);
        List<StudyTask> tasks = studyTaskService.getOverdueTasks(userId);

        return ApiResponse.success(tasks);
    }

    /**
     * 完成任务
     */
    @PostMapping("/{id}/complete")
    public ApiResponse<StudyTask> completeTask(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserId(authHeader);
        StudyTask task = studyTaskService.completeTask(userId, id);

        return ApiResponse.success("任务已完成", task);
    }

    /**
     * 从Token解析userId
     */
    private Integer extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("未提供Token");
        }
        String token = authHeader.substring(7);
        return jwtUtil.getUserIdFromToken(token).intValue();
    }
}