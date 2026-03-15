package com.smartcampus.controller;

import com.smartcampus.dto.ApiResponse;
import com.smartcampus.entity.ReviewSuggestion;
import com.smartcampus.entity.StudyTask;
import com.smartcampus.service.StudyPlanService;
import com.smartcampus.service.ReviewSuggestionService;
import com.smartcampus.service.StudyTaskService;
import com.smartcampus.utils.JwtUtil;
import com.smartcampus.vo.StudyTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/study/tasks")
@RequiredArgsConstructor
public class StudyTaskController {

    private final StudyTaskService studyTaskService;
    private final JwtUtil jwtUtil;

    private final ReviewSuggestionService reviewSuggestionService;  // 注入 ReviewSuggestionService
    private final StudyPlanService studyPlanService;  // 注入 StudyPlanService

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
     * 完成普通复习任务
     */
    @PostMapping("/{id}/complete")
    public ApiResponse<Void> completeReviewTask(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserId(authHeader);
        studyTaskService.completeReviewTask(userId, id);

        return ApiResponse.success("任务已完成", null);
    }

    /**
     * 获取复习任务详情（用于复习详情页）
     * GET /api/study/tasks/review/{id}
     */
    @GetMapping("/review/{planId}")  // 保持用 planId
    public ApiResponse<StudyTaskVO> getReviewTaskDetail(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer planId) {  // 参数名保持 planId

        Integer userId = extractUserId(authHeader);

        // 通过 planId 获取当前复习任务
        StudyTask task = studyTaskService.getCurrentReviewTask(userId, planId);

        // 获取当前建议
        ReviewSuggestion suggestion = reviewSuggestionService.getCurrentSuggestion(task.getId());

        StudyTaskVO vo = new StudyTaskVO();
        BeanUtils.copyProperties(task, vo);
        vo.setCurrentSuggestion(suggestion);

        return ApiResponse.success(vo);
    }

    /**
     * 获取某个学习计划的所有复习任务（历史记录）
     * GET /api/study/tasks/plan/{planId}/history
     */
    @GetMapping("/plan/{planId}/history")
    public ApiResponse<List<StudyTask>> getReviewTaskHistory(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer planId) {

        Integer userId = extractUserId(authHeader);
        List<StudyTask> tasks = studyTaskService.getTasksByPlanId(userId, planId);

        return ApiResponse.success(tasks);
    }

    /**
     * 更新复习任务内容（AI生成的复习计划）
     * PUT /api/study/tasks/{id}/content
     */
    @PutMapping("/{id}/content")
    public ApiResponse<ReviewSuggestion> updateTaskContent(
            // 返回类型改为 ReviewSuggestion
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id,
            @RequestBody String content) {

        Integer userId = extractUserId(authHeader);
        ReviewSuggestion suggestion = reviewSuggestionService.createSuggestion(userId, id, content);

        return ApiResponse.success("生成成功", suggestion);
    }

    /**
     * 获取用户所有复习任务（包括待复习和已完成）
     */
    @GetMapping("/all")
    public ApiResponse<List<StudyTask>> getAllTasks(
            @RequestHeader("Authorization") String authHeader) {

        Integer userId = extractUserId(authHeader);
        List<StudyTask> tasks = studyTaskService.getAllTasks(userId);

        return ApiResponse.success(tasks);
    }

    @PostMapping("/{taskId}/suggestions")
    public ApiResponse<ReviewSuggestion> createSuggestion(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer taskId,
            @RequestBody String content) {

        Integer userId = extractUserId(authHeader);
        ReviewSuggestion suggestion = reviewSuggestionService.createSuggestion(userId, taskId, content);
        return ApiResponse.success(suggestion);
    }

    @GetMapping("/{taskId}/suggestions")
    public ApiResponse<List<ReviewSuggestion>> getTaskSuggestions(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer taskId) {

        Integer userId = extractUserId(authHeader);
        studyTaskService.getTaskById(userId, taskId);
        List<ReviewSuggestion> suggestions = reviewSuggestionService.getTaskSuggestions(taskId);
        return ApiResponse.success(suggestions);
    }

    @GetMapping("/plan/{planId}/suggestions")
    public ApiResponse<List<ReviewSuggestion>> getPlanSuggestions(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer planId) {

        Integer userId = extractUserId(authHeader);
        List<ReviewSuggestion> suggestions = reviewSuggestionService.getPlanSuggestions(planId);
        return ApiResponse.success(suggestions);
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