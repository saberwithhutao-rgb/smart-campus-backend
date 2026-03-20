package com.smartcampus.service;

import com.smartcampus.entity.StudyTask;
import com.smartcampus.exception.BusinessException;
import com.smartcampus.repository.StudyTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAdviceService {

    private final QianWenService qianWenService;
    private final StudyTaskRepository studyTaskRepository;

    /**
     * 为复习任务生成AI复习建议
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param title 任务标题
     * @param description 任务描述（可选）
     * @param reviewStage 复习阶段（1-5）
     */
    @Transactional
    public String generateReviewAdvice(Long userId, Long taskId, String title, String description, Integer reviewStage) {
        log.info("开始为复习任务 {} 生成第{}次复习建议, title={}, description={}", taskId, reviewStage, title, description);

        // 1. 验证任务存在且属于该用户
        StudyTask task = studyTaskRepository.findById(Math.toIntExact(taskId))
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!task.getUserId().equals(Math.toIntExact(userId))) {
            throw new BusinessException(403, "无权访问此任务");
        }

        // 2. 构建提示词（根据参数动态调整）
        String prompt = buildPrompt(title, description, reviewStage);

        // 3. 调用AI服务
        String adviceText;
        try {
            adviceText = callAIService(prompt);
            if (adviceText == null || adviceText.trim().isEmpty()) {
                throw new Exception("AI服务返回空响应");
            }
        } catch (Exception e) {
            log.error("AI服务调用失败: {}", e.getMessage());
            throw new BusinessException(500, "生成复习建议失败: " + e.getMessage());
        }

        log.info("复习建议生成成功，任务ID: {}", taskId);
        return adviceText;
    }

    /**
     * 构建提示词 - 根据参数是否为空动态调整
     */
    private String buildPrompt(String title, String description, Integer reviewStage) {
        StringBuilder prompt = new StringBuilder();

        // 角色定位
        prompt.append("你是一位学习科学专家，熟悉艾宾浩斯遗忘曲线和高效复习方法。\n\n");

        // 复习任务信息
        prompt.append("【复习任务】\n");

        if (title != null && !title.trim().isEmpty()) {
            prompt.append("- 任务名称：").append(title).append("\n");
        } else {
            prompt.append("- 任务名称：未指定\n");
        }

        if (description != null && !description.trim().isEmpty()) {
            prompt.append("- 任务描述：").append(description).append("\n");
        }

        prompt.append("- 复习阶段：第").append(reviewStage).append("次复习\n");

        // 复习阶段说明（基于艾宾浩斯遗忘曲线）
        prompt.append(getReviewStageDescription(reviewStage));

        // 复习特点指导
        prompt.append(buildReviewStageGuidance(reviewStage));

        // 制定要求
        prompt.append("""
                
                【制定要求】
                1. 输出格式：使用 Markdown 格式，层次清晰
                
                2. 复习建议必须包含：
                   - 核心知识点回顾
                   - 重点记忆内容
                   - 练习题或思考题（2-3个）
                   - 拓展思考题（可选）
                
                3. 输出约束：
                   - 不要任何开场白（如"以下是为您制定的复习建议"）
                   - 不要任何结束语（如"祝您复习顺利"）
                   - 直接输出复习内容
                
                【复习建议】
                """);

        return prompt.toString();
    }

    /**
     * 获取复习阶段说明（基于艾宾浩斯遗忘曲线）
     */
    private String getReviewStageDescription(Integer reviewStage) {
        return switch (reviewStage) {
            case 1 -> """
                - 复习时间：学习后1天
                - 遗忘情况：遗忘约50-60%
                - 复习重点：快速回顾，巩固基础框架
                """;
            case 2 -> """
                - 复习时间：学习后3天
                - 遗忘情况：遗忘约70-80%
                - 复习重点：加深理解，补充细节
                """;
            case 3 -> """
                - 复习时间：学习后7天
                - 遗忘情况：遗忘约80-90%
                - 复习重点：强化记忆，查漏补缺
                """;
            case 4 -> """
                - 复习时间：学习后15天
                - 遗忘情况：遗忘约90%以上
                - 复习重点：综合运用，知识整合
                """;
            case 5 -> """
                - 复习时间：学习后30天
                - 遗忘情况：长时记忆巩固
                - 复习重点：长期保持，拓展应用
                """;
            default -> """
                - 复习时间：长期巩固阶段
                - 复习重点：知识体系完善，查漏补缺
                """;
        };
    }

    /**
     * 构建复习阶段指导（根据第几次复习给出针对性建议）
     */
    private String buildReviewStageGuidance(Integer reviewStage) {
        StringBuilder guidance = new StringBuilder();
        guidance.append("\n【本次复习特点】\n");

        switch (reviewStage) {
            case 1 -> guidance.append("""
                这是第一次复习（学习后1天）：
                - 重点是快速回顾整体框架
                - 不要纠结细节，先建立知识图谱
                - 建议采用"回忆法"，先自己回想再对照
                """);
            case 2 -> guidance.append("""
                这是第二次复习（学习后3天）：
                - 重点补充第一次遗漏的细节
                - 开始关注概念之间的联系
                - 建议采用"提问法"，自问自答
                """);
            case 3 -> guidance.append("""
                这是第三次复习（学习后7天）：
                - 重点强化记忆薄弱环节
                - 进行知识点串联和对比
                - 建议做练习题检验掌握程度
                """);
            case 4 -> guidance.append("""
                这是第四次复习（学习后15天）：
                - 重点综合运用所学知识
                - 进行跨章节、跨知识点整合
                - 尝试解决综合型问题
                """);
            case 5 -> guidance.append("""
                这是第五次复习（学习后30天）：
                - 重点知识体系完善
                - 建立长期记忆
                - 进行拓展学习，关联新知识
                """);
            default -> guidance.append("""
                    这是第""").append(reviewStage).append("""
                    次复习（长期巩固阶段）：
                    - 重点是维持长期记忆
                    - 定期快速回顾
                    - 查漏补缺，保持知识活性
                    """);
        }

        return guidance.toString();
    }

    /**
     * 调用AI服务
     */
    private String callAIService(String prompt) throws Exception {
        try {
            log.info("正在调用AI服务生成复习建议...");
            return qianWenService.askQuestion(prompt, Collections.emptyList(), "qwen-max")
                    .block(java.time.Duration.ofSeconds(120));
        } catch (Exception e) {
            log.error("调用AI服务失败: {}", e.getMessage(), e);
            throw new Exception("AI服务调用失败: " + e.getMessage());
        }
    }
}