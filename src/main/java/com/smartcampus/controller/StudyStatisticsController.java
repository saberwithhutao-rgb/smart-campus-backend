package com.smartcampus.controller;

import com.smartcampus.service.StudyStatisticsService;
import com.smartcampus.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学习统计控制器
 */
@RestController
@RequestMapping("/api/study")
public class StudyStatisticsController {

    @Autowired
    private StudyStatisticsService studyStatisticsService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 获取学习统计数据
     *
     * @param timeRange 时间范围：today/week/month
     * @param request HTTP请求（用于获取token）
     * @return 统计数据Map
     */
    @GetMapping("/statistics")
    public Map<String, Object> getStudyStatistics(
            @RequestParam("timeRange") String timeRange,
            HttpServletRequest request) {

        // 从请求头获取token并解析userId
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未授权访问");
        }

        String token = authHeader.substring(7);
        Long userIdLong = jwtUtil.getUserIdFromToken(token);
        Integer userId = userIdLong.intValue();

        return studyStatisticsService.getStudyStatistics(userId, timeRange);
    }
}