package com.smartcampus.service;

import com.smartcampus.dto.ApiResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 学习建议服务接口
 */
public interface StudySuggestionService {

    /**
     * 获取学习建议（同步）
     *
     * @param userId 用户ID
     * @param timeRange 时间范围：today/week/month
     * @return 统一格式的响应结果
     */
    ApiResponse getStudySuggestions(Integer userId, String timeRange);

    /**
     * 异步获取学习建议
     *
     * @param userId 用户ID
     * @param timeRange 时间范围：today/week/month
     * @return CompletableFuture
     */
    CompletableFuture<ApiResponse> getStudySuggestionsAsync(Integer userId, String timeRange);
}