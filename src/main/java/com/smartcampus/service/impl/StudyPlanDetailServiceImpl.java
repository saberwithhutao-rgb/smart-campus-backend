// src/main/java/com/smartcampus/service/impl/StudyPlanDetailServiceImpl.java
package com.smartcampus.service.impl;

import com.smartcampus.entity.StudyPlanDetail;
import com.smartcampus.repository.StudyPlanDetailRepository;
import com.smartcampus.service.QianWenService;
import com.smartcampus.service.StudyPlanDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class StudyPlanDetailServiceImpl implements StudyPlanDetailService {

    private final QianWenService qianWenService;
    private final StudyPlanDetailRepository studyPlanDetailRepository;

    @Override
    @Transactional
    public Map<String, Object> createPlanDetailForUser(Long studyPlanId, String subject, String duration, String level) throws Exception {
        log.info("开始为学习计划 {} 生成详情: subject={}, duration={}, level={}", studyPlanId, subject, duration, level);

        // 1. 构建提示词
        String prompt = buildPrompt(subject, duration, level);

        // 2. 调用AI服务 - 直接获取文本
        String planText = null;
        try {
            planText = callAIService(prompt);
            if (planText == null || planText.trim().isEmpty()) {
                throw new Exception("AI服务返回空响应");
            }
        } catch (Exception e) {
            log.error("AI服务调用失败，不保存到数据库: {}", e.getMessage());
            throw e;  // 直接抛出异常，不保存
        }

        // 3. 保存到数据库
        StudyPlanDetail detail = new StudyPlanDetail();
        detail.setStudyPlanId(Math.toIntExact(studyPlanId));
        detail.setDuration(duration);
        detail.setLevel(level);
        detail.setPlanDetails(planText);
        detail.setCreatedAt(LocalDateTime.now());

        StudyPlanDetail savedDetail = studyPlanDetailRepository.save(detail);

        // 4. 返回数据
        Map<String, Object> result = new HashMap<>();
        result.put("plan", planText);
        result.put("detailId", savedDetail.getId());

        log.info("计划详情生成并保存成功，detailId: {}", savedDetail.getId());
        return result;
    }

    private String buildPrompt(String subject, String duration, String level) {
        return String.format(
                """
                        请为%s级别的学生制定一份为期%s的'%s'学习计划。
                        
                        要求：
                        1. 直接输出计划内容，不要任何开场白（如'以下是为您制定的计划'等）
                        2. 不要任何结束语（如'希望这个计划对你有帮助'等）
                        3. 直接开始写计划，用 Markdown 格式
                        4. 计划要详细，包括每天的学习内容、任务和资源
                        
                        现在开始输出计划：""",
                level, duration, subject
        );
    }

    private String callAIService(String prompt) throws Exception {
        try {
            log.info("正在调用AI服务...");
            return qianWenService.askQuestion(prompt, Collections.emptyList(), "qwen-max")
                    .block(java.time.Duration.ofSeconds(120));
        } catch (Exception e) {
            log.error("调用AI服务失败: {}", e.getMessage(), e);
            throw new Exception("AI服务调用失败: " + e.getMessage());
        }
    }
}