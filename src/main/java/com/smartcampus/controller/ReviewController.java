package com.smartcampus.controller;

import com.smartcampus.dto.*;
import com.smartcampus.service.ReviewService;
import com.smartcampus.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final JwtUtil jwtUtil;

    /**
     * 11.1 获取复习计划
     * GET /ai/review
     */
    @GetMapping("/review")
    public ApiResponse<ReviewPlan> getReviewPlan(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) Long planId) {

        Integer userId = extractUserIdFromToken(authHeader);
        ReviewPlan plan = reviewService.getReviewPlan(userId, planId);

        return ApiResponse.success(plan);
    }

    /**
     * 11.2 生成复习曲线
     * POST /ai/review/generate
     */
    @PostMapping("/review/generate")
    public ApiResponse<ReviewCurve> generateReviewCurve(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody GenerateReviewRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        ReviewCurve curve = reviewService.generateReviewCurve(userId, request);

        return ApiResponse.success("复习曲线生成成功", curve);
    }

    /**
     * 11.3 标记难点
     * POST /ai/study/difficulty
     */
    @PostMapping("/study/difficulty")
    public ApiResponse<DifficultyMark> markDifficulty(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody MarkDifficultyRequest request) {

        Integer userId = extractUserIdFromToken(authHeader);
        DifficultyMark mark = reviewService.markDifficulty(userId, request);

        return ApiResponse.success("标记成功", mark);
    }

    private Integer extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("未提供Token");
        }
        String token = authHeader.substring(7);
        Long userIdLong = jwtUtil.getUserIdFromToken(token);
        if (userIdLong == null) {
            throw new RuntimeException("Token无效");
        }
        return userIdLong.intValue();
    }
}