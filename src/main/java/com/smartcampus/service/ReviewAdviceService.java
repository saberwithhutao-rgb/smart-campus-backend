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
     */
    @Transactional
    public String generateReviewAdvice(Long userId, Long taskId, String title, Integer reviewStage) {
        log.info("开始为复习任务 {} 生成第{}次复习建议", taskId, reviewStage);

        // 1. 验证任务存在且属于该用户
        StudyTask task = studyTaskRepository.findById(Math.toIntExact(taskId))
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!task.getUserId().equals(Math.toIntExact(userId))) {
            throw new BusinessException(403, "无权访问此任务");
        }

        // 2. 构建提示词
        String prompt = buildPrompt(title, reviewStage);

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
     * 构建提示词
     */
    private String buildPrompt(String title, Integer reviewStage) {
        String[] stageDesc = {
                "第一次复习（1天后）—— 侧重基础概念和框架",
                "第二次复习（3天后）—— 侧重细节理解和应用",
                "第三次复习（7天后）—— 侧重细节理解和应用",
                "第四次复习（15天后）—— 侧重综合运用和拓展",
                "第五次复习（30天后）—— 侧重综合运用和拓展"
        };

        String currentStageDesc = reviewStage <= 5 ? stageDesc[reviewStage - 1] : "第" + reviewStage + "次复习";

        // 计算已完成的阶段
        int completedStages = reviewStage - 1;

        return String.format(
                """
                请为计划标题为"%s"的复习任务生成第%d次复习阶段的复习建议。
                
                复习任务共5个阶段（基于艾宾浩斯遗忘曲线），该用户已完成前%d次复习，即将进行第%d次复习。
                第%d次复习的特点是：%s
                
                要求：
                1. 直接输出复习内容，不要任何开场白和结束语
                2. 用 Markdown 格式
                3. 复习建议应包括：
                   - 核心知识点回顾
                   - 需要重点记忆的内容
                   - 相关练习题或思考题（2-3个）
                   - 拓展思考题（可选）
                
                现在开始输出复习内容：""",
                title,
                reviewStage,
                completedStages,
                reviewStage,
                reviewStage,
                currentStageDesc
        );
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