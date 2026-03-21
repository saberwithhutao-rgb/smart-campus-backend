package com.smartcampus.service.impl;

import com.smartcampus.dto.ApiResponse;
import com.smartcampus.service.QianWenService;
import com.smartcampus.service.StudyStatisticsService;
import com.smartcampus.service.StudySuggestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 学习建议服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudySuggestionServiceImpl implements StudySuggestionService {

    private final StudyStatisticsService studyStatisticsService;
    private final QianWenService qianWenService;

    @Override
    public ApiResponse getStudySuggestions(Integer userId, String timeRange) {
        log.info("开始获取学习建议: userId={}, timeRange={}", userId, timeRange);

        try {
            // 1. 获取统计数据
            Map<String, Object> statistics = studyStatisticsService.getStudyStatistics(userId, timeRange);
            log.info("统计数据获取成功: {}", statistics);

            // 2. 构建 AI 提示词
            String prompt = buildPrompt(statistics, timeRange);
            log.info("AI提示词构建完成，长度: {}", prompt.length());

            // 3. 调用 AI 生成建议
            String aiResponse = qianWenService.askQuestion(prompt, Collections.emptyList(), "qwen-max")
                    .block(Duration.ofSeconds(90));

            log.info("AI响应长度: {}", aiResponse != null ? aiResponse.length() : 0);

            // 4. 解析 AI 响应为建议列表
            List<String> suggestions = parseSuggestions(aiResponse);

            // 5. 构建返回结果
            Map<String, Object> data = new HashMap<>();
            data.put("suggestions", suggestions);

            return ApiResponse.success(data);

        } catch (Exception e) {
            log.error("生成学习建议失败", e);
            return ApiResponse.error(500, "生成学习建议失败: " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<ApiResponse> getStudySuggestionsAsync(Integer userId, String timeRange) {
        log.info("异步获取学习建议: userId={}, timeRange={}", userId, timeRange);

        return CompletableFuture.supplyAsync(() -> getStudySuggestions(userId, timeRange));
    }

    /**
     * 构建 AI 提示词
     */
    private String buildPrompt(Map<String, Object> statistics, String timeRange) {
        // 获取时间范围文本
        String timeRangeText = switch (timeRange) {
            case "today" -> "今天";
            case "week" -> "过去一周";
            case "month" -> "过去一个月";
            default -> "近期";
        };

        // 获取统计数据
        int totalPlanCount = (int) statistics.getOrDefault("totalPlanCount", 0);
        int completedPlanCount = (int) statistics.getOrDefault("completedPlanCount", 0);
        double completionRate = (double) statistics.getOrDefault("completionRate", 0.0);
        int overduePlanCount = (int) statistics.getOrDefault("overduePlanCount", 0);
        int unfinishedCount = (int) statistics.getOrDefault("unfinishedCount", 0);

        // 难度分布
        Map<String, Object> difficultyDist = (Map<String, Object>) statistics.get("difficultyDistribution");
        List<Map<String, Object>> difficultyDetails = (List<Map<String, Object>>) difficultyDist.get("details");

        // 计划类型分布
        Map<String, Object> planTypeDist = (Map<String, Object>) statistics.get("planTypeDistribution");
        List<Map<String, Object>> planTypeDetails = (List<Map<String, Object>>) planTypeDist.get("details");

        // 科目分布
        Map<String, Integer> subjectDist = (Map<String, Integer>) statistics.get("subjectDistribution");

        // 构建提示词
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位智慧校园的学习规划专家，请根据以下学习统计数据，为用户生成个性化的学习建议。\n\n");
        prompt.append("【统计周期】").append(timeRangeText).append("\n");
        prompt.append("【学习统计数据】\n");
        prompt.append("- 总计划数：").append(totalPlanCount).append("个\n");
        prompt.append("- 已完成计划数：").append(completedPlanCount).append("个\n");
        prompt.append("- 完成率：").append(String.format("%.1f%%", completionRate * 100)).append("\n");
        prompt.append("- 未完成计划数：").append(unfinishedCount).append("个\n");
        prompt.append("- 延期计划数：").append(overduePlanCount).append("个\n\n");

        prompt.append("【难度分布】\n");
        for (Map<String, Object> item : difficultyDetails) {
            int count = (int) item.get("count");
            double percentage = (double) item.get("percentage");
            prompt.append("- ").append(item.get("type"))
                    .append("：").append(count).append("个")
                    .append(" (").append(String.format("%.1f%%", percentage * 100)).append(")\n");
        }
        prompt.append("\n");

        prompt.append("【计划类型分布】\n");
        for (Map<String, Object> item : planTypeDetails) {
            int count = (int) item.get("count");
            double percentage = (double) item.get("percentage");
            prompt.append("- ").append(item.get("type"))
                    .append("：").append(count).append("个")
                    .append(" (").append(String.format("%.1f%%", percentage * 100)).append(")\n");
        }
        prompt.append("\n");

        if (subjectDist != null && !subjectDist.isEmpty()) {
            prompt.append("【科目分布】\n");
            subjectDist.forEach((subject, count) -> {
                prompt.append("- ").append(subject).append("：").append(count).append("个计划\n");
            });
            prompt.append("\n");
        }

        prompt.append("【生成要求】\n");
        prompt.append("1. 基于以上数据，给出3-5条具体、实用的学习建议\n");
        prompt.append("2. 建议要针对用户的学习情况，包括：\n");
        prompt.append("   - 如何提高完成率\n");
        prompt.append("   - 如何减少延期计划\n");
        prompt.append("   - 各科目的学习建议\n");
        prompt.append("   - 难度平衡建议\n");
        prompt.append("3. 每条建议用简洁的语言表达\n");
        prompt.append("4. 直接输出建议列表，每条建议占一行，不要其他说明文字\n\n");
        prompt.append("【学习建议】\n");

        return prompt.toString();
    }

    /**
     * 解析 AI 响应为建议列表
     */
    private List<String> parseSuggestions(String aiResponse) {
        List<String> suggestions = new ArrayList<>();

        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            suggestions.add("暂无学习建议，请先创建学习计划");
            return suggestions;
        }

        // 按行分割，过滤空行
        String[] lines = aiResponse.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                // 移除可能的序号前缀（如 "1. "、"- " 等）
                String cleaned = trimmed.replaceAll("^\\d+\\.\\s*", "")
                        .replaceAll("^[-*•]\\s*", "")
                        .trim();
                if (!cleaned.isEmpty()) {
                    suggestions.add(cleaned);
                }
            }
        }

        // 如果没有解析出任何建议，使用默认建议
        if (suggestions.isEmpty()) {
            suggestions.add("继续努力学习，保持良好学习习惯");
            suggestions.add("合理安排时间，避免计划延期");
            suggestions.add("适当调整计划难度，循序渐进");
        }

        return suggestions;
    }
}