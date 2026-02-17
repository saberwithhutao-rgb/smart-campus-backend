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
    private final JwtUtil jwtUtil;

    /**
     * 1. 获取学习计划列表
     */
    @GetMapping("/plans")
    public ApiResponse<PageResult<StudyPlan>> getPlans(
            @RequestHeader("Authorization") String authHeader,  // ✅ required = true，必须带token！
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planType,
            @RequestParam(required = false) String subject) {

        // ✅ 从Token解析userId，没有默认值！
        Integer userId = extractUserIdFromToken(authHeader);

        PageResult<StudyPlan> result = studyPlanService.getPlans(
                userId, page, size, status, planType, subject);

        return ApiResponse.success(result);
    }

    /**
     * 2. 创建学习计划
     */
    @PostMapping("/plans")
    public ApiResponse<StudyPlan> createPlan(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreatePlanRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.createPlan(userId, request);

        return ApiResponse.success("创建成功", plan);
    }

    /**
     * 3. 更新学习计划
     */
    @PutMapping("/plans/{id}")
    public ApiResponse<StudyPlan> updatePlan(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id,
            @Valid @RequestBody UpdatePlanRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.updatePlan(userId, id, request);

        return ApiResponse.success("更新成功", plan);
    }

    /**
     * 4. 删除学习计划
     */
    @DeleteMapping("/plans/{id}")
    public ApiResponse<Void> deletePlan(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserIdFromToken(authHeader);
        studyPlanService.deletePlan(userId, id);

        return ApiResponse.success("删除成功", null);
    }

    /**
     * 6. 获取单个学习计划
     */
    @GetMapping("/plans/{id}")
    public ApiResponse<StudyPlan> getPlanById(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.getPlanById(userId, id);

        return ApiResponse.success(plan);
    }

    /**
     * 7. 切换完成状态
     */
    @PostMapping("/plans/{id}/toggle")
    public ApiResponse<StudyPlan> toggleComplete(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Integer id) {

        Integer userId = extractUserIdFromToken(authHeader);
        StudyPlan plan = studyPlanService.toggleComplete(userId, id);

        return ApiResponse.success("状态切换成功", plan);
    }

    /**
     * 8. 获取学习日程
     */
    @GetMapping("/schedule")
    public ApiResponse<List<StudyPlan>> getSchedule(
            @RequestHeader("Authorization") String authHeader,
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
     * 没有默认值！token无效直接抛异常！
     */
    private Integer extractUserIdFromToken(String authHeader) {
        // 1. 验证token是否存在
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("未提供Token或Token格式错误");
        }

        // 2. 提取token
        String token = authHeader.substring(7);

        try {
            // 3. 解析token获取userId
            Long userIdLong = jwtUtil.getUserIdFromToken(token);

            if (userIdLong == null) {
                throw new RuntimeException("Token中不存在userId");
            }

            log.info("Token解析成功，userId: {}", userIdLong);
            return userIdLong.intValue();

        } catch (Exception e) {
            log.error("Token解析失败: {}", e.getMessage());
            throw new RuntimeException("无效的Token", e);
        }
    }
}