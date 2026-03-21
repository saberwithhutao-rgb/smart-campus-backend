package com.smartcampus.service;

import java.util.Map;

/**
 * 学习统计服务接口
 */
public interface StudyStatisticsService {

    /**
     * 获取学习统计数据
     *
     * @param userId 用户ID
     * @param timeRange 时间范围：today/week/month
     * @return 统计数据Map
     */
    Map<String, Object> getStudyStatistics(Integer userId, String timeRange);
}