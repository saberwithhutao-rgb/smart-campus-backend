package com.smartcampus.service.impl;

import com.smartcampus.entity.StudyPlan;
import com.smartcampus.repository.StudyPlanRepository;
import com.smartcampus.service.StudyStatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习统计服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyStatisticsServiceImpl implements StudyStatisticsService {

    private final StudyPlanRepository studyPlanRepository;

    @Override
    public Map<String, Object> getStudyStatistics(Integer userId, String timeRange) {
        log.info("获取学习统计数据: userId={}, timeRange={}", userId, timeRange);

        // 1. 计算时间范围
        LocalDate startDate = getStartDateByTimeRange(timeRange);
        LocalDate endDate = LocalDate.now();

        log.info("查询时间范围: {} 到 {}", startDate, endDate);

        // 2. 查询用户在该时间范围内的学习计划
        List<StudyPlan> plans = studyPlanRepository.findByUserIdAndStartDateBetween(
                userId, startDate, endDate
        );

        log.info("查询到 {} 个学习计划", plans.size());

        // 3. 统计数据
        Map<String, Object> statistics = new HashMap<>();

        // 基础统计
        int totalPlanCount = plans.size();
        int completedPlanCount = (int) plans.stream()
                .filter(p -> "completed".equals(p.getStatus()))
                .count();
        int unfinishedCount = totalPlanCount - completedPlanCount;
        int overduePlanCount = (int) plans.stream()
                .filter(p -> !"completed".equals(p.getStatus())
                        && p.getEndDate() != null
                        && p.getEndDate().isBefore(LocalDate.now()))
                .count();
        double completionRate = totalPlanCount > 0 ? (double) completedPlanCount / totalPlanCount : 0;

        statistics.put("totalPlanCount", totalPlanCount);
        statistics.put("completedPlanCount", completedPlanCount);
        statistics.put("unfinishedCount", unfinishedCount);
        statistics.put("overduePlanCount", overduePlanCount);
        statistics.put("completionRate", completionRate);

        // 难度分布
        Map<String, Object> difficultyDistribution = calculateDifficultyDistribution(plans, totalPlanCount);
        statistics.put("difficultyDistribution", difficultyDistribution);

        // 计划类型分布
        Map<String, Object> planTypeDistribution = calculatePlanTypeDistribution(plans, totalPlanCount);
        statistics.put("planTypeDistribution", planTypeDistribution);

        // 科目分布
        Map<String, Integer> subjectDistribution = plans.stream()
                .filter(p -> p.getSubject() != null && !p.getSubject().isEmpty())
                .collect(Collectors.groupingBy(
                        StudyPlan::getSubject,
                        Collectors.summingInt(p -> 1)
                ));
        statistics.put("subjectDistribution", subjectDistribution);

        log.info("统计完成: 总计划={}, 完成={}, 完成率={}%",
                totalPlanCount, completedPlanCount, String.format("%.2f", completionRate * 100));

        return statistics;
    }

    /**
     * 根据时间范围获取开始日期
     */
    private LocalDate getStartDateByTimeRange(String timeRange) {
        LocalDate today = LocalDate.now();
        return switch (timeRange) {
            case "today" -> today;
            case "week" -> today.minusDays(7);
            case "month" -> today.minusDays(30);
            default -> today.minusDays(7); // 默认一周
        };
    }

    /**
     * 计算难度分布
     */
    private Map<String, Object> calculateDifficultyDistribution(List<StudyPlan> plans, int totalCount) {
        Map<String, Integer> countMap = new HashMap<>();
        countMap.put("简单", 0);
        countMap.put("中等", 0);
        countMap.put("困难", 0);

        for (StudyPlan plan : plans) {
            String difficulty = plan.getDifficulty();
            if (difficulty == null) continue;

            switch (difficulty) {
                case "easy" -> countMap.put("简单", countMap.get("简单") + 1);
                case "medium" -> countMap.put("中等", countMap.get("中等") + 1);
                case "hard" -> countMap.put("困难", countMap.get("困难") + 1);
                default -> {}
            }
        }

        List<Map<String, Object>> details = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countMap.entrySet()) {
            int count = entry.getValue();
            double percentage = totalCount > 0 ? (double) count / totalCount : 0;
            Map<String, Object> item = new HashMap<>();
            item.put("type", entry.getKey());
            item.put("count", count);
            item.put("percentage", percentage);
            details.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("details", details);
        return result;
    }

    /**
     * 计算计划类型分布
     */
    private Map<String, Object> calculatePlanTypeDistribution(List<StudyPlan> plans, int totalCount) {
        Map<String, Integer> countMap = getStringIntegerMap(plans);

        List<Map<String, Object>> details = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countMap.entrySet()) {
            int count = entry.getValue();
            double percentage = totalCount > 0 ? (double) count / totalCount : 0;
            Map<String, Object> item = new HashMap<>();
            item.put("type", entry.getKey());
            item.put("count", count);
            item.put("percentage", percentage);
            details.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("details", details);
        return result;
    }

    private static Map<String, Integer> getStringIntegerMap(List<StudyPlan> plans) {
        Map<String, Integer> countMap = new HashMap<>();
        countMap.put("学习计划", 0);
        countMap.put("复习计划", 0);
        countMap.put("项目计划", 0);

        for (StudyPlan plan : plans) {
            String planType = plan.getPlanType();
            if (planType == null) continue;

            switch (planType) {
                case "learning" -> countMap.put("学习计划", countMap.get("学习计划") + 1);
                case "review" -> countMap.put("复习计划", countMap.get("复习计划") + 1);
                case "project" -> countMap.put("项目计划", countMap.get("项目计划") + 1);
                default -> {}
            }
        }
        return countMap;
    }
}