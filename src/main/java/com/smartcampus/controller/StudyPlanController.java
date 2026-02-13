package com.smartcampus.controller;

import com.smartcampus.dto.*;
import com.smartcampus.entity.StudyPlan;
import com.smartcampus.service.StudyPlanService;
import com.smartcampus.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/study")
@RequiredArgsConstructor
public class StudyPlanController {

    private final StudyPlanService studyPlanService;
    private final JwtUtil jwtUtil;  // ✅ 注入JwtUtil

    /**
     * 1. 获取学习计划列表
     * GET /api/study/plans
     */
    @GetMapping("/plans")
    public ApiResponse<PageResult<StudyPlan>> getPlans(
            @RequestHeader(value = "Authorization", required = false) String authHeader,  // ✅ 自己拿header
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planType,
            @RequestParam(required = false) String subject) {

        // ✅ 从Token解析userId
        Integer userId = extractUserIdFromToken(authHeader);

        PageResult<StudyPlan> result = studyPlanService.getPlans(
                userId, page, size, status, planType, subject);

        return ApiResponse.success(result);
    }

    /**
     * 2. 创建学习计划
     * POST /api/study/plans
     */
    @PostMapping("/plans")
    public ApiResponse<StudyPlan> createPlan(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CreatePlanRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.createPlan(userId, request);

        return ApiResponse.success("创建成功", plan);
    }

    /**
     * 3. 更新学习计划
     * PUT /api/study/plans/{id}
     */
    @PutMapping("/plans/{id}")
    public ApiResponse<StudyPlan> updatePlan(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Integer id,
            @Valid @RequestBody UpdatePlanRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.updatePlan(userId, id, request);

        return ApiResponse.success("更新成功", plan);
    }

    /**
     * 4. 删除学习计划
     * DELETE /api/study/plans/{id}
     */
    @DeleteMapping("/plans/{id}")
    public ApiResponse<Void> deletePlan(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserIdFromToken(authHeader);
        studyPlanService.deletePlan(userId, id);

        return ApiResponse.success("删除成功", null);
    }

    /**
     * 5. 更新进度
     * PATCH /api/study/plans/{id}/progress
     */
    @PatchMapping("/plans/{id}/progress")
    public ApiResponse<StudyPlan> updateProgress(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Integer id,
            @Valid @RequestBody UpdateProgressRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.updateProgress(userId, id, request);

        String message = request.getProgressPercent() >= 100 ?
                "恭喜！计划已完成" : "进度更新成功";

        return ApiResponse.success(message, plan);
    }

    /**
     * 6. 获取单个学习计划
     * GET /api/study/plans/{id}
     */
    @GetMapping("/plans/{id}")
    public ApiResponse<StudyPlan> getPlanById(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.getPlanById(userId, id);

        return ApiResponse.success(plan);
    }

    /**
     * 7. 切换完成状态
     * POST /api/study/plans/{id}/toggle
     */
    @PostMapping("/plans/{id}/toggle")
    public ApiResponse<StudyPlan> toggleComplete(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.toggleComplete(userId, id);

        return ApiResponse.success("状态切换成功", plan);
    }

    /**
     * 8. 获取学习日程
     * GET /api/study/schedule
     */
    @GetMapping("/schedule")
    public ApiResponse<List<StudyPlan>> getSchedule(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) Integer planId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {

        Integer userId = extractUserIdFromToken(authHeader);
        List<StudyPlan> schedule = studyPlanService.getSchedule(userId, planId, startDate, endDate);

        return ApiResponse.success(schedule);
    }

    // ==================== 从Token解析userId的核心方法 ====================

    /**
     * 从Authorization头中提取并解析userId
     * 完全模仿你的 /user/profile 接口的写法
     */
    private Integer extractUserIdFromToken(String authHeader) {
        try {
            // 1. 如果没有token，返回默认用户ID（方便测试）
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("未提供Token，使用默认用户ID: 3");
                return 3;  // 你的测试用户ID
            }

            // 2. 提取token
            String token = authHeader.substring(7);

            // 3. 用你的JwtUtil解析token获取userId
            Long userIdLong = jwtUtil.getUserIdFromToken(token);

            if (userIdLong == null) {
                log.warn("Token解析失败，使用默认用户ID: 3");
                return 3;
            }

            log.info("Token解析成功，userId: {}", userIdLong);
            return userIdLong.intValue();

        } catch (Exception e) {
            // 4. 任何异常都返回默认用户ID，保证接口可用
            log.error("Token解析异常: {}, 使用默认用户ID: 3", e.getMessage());
            e.printStackTrace();
            return 3;
        }
    }
}