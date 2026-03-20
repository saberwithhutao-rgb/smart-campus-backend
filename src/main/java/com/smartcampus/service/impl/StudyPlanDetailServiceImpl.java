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
    public Map<String, Object> createPlanDetailForUser(String title, Long studyPlanId,
                                                       String subject, String duration,
                                                       String level) throws Exception {
        log.info("开始为学习计划生成详情: title={}, studyPlanId={}, subject={}, duration={}, level={}",
                title, studyPlanId, subject, duration, level);

        // 1. 构建智能提示词
        String prompt = buildIntelligentPrompt(title, subject, duration, level);

        // 2. 调用AI服务
        String planText = null;
        try {
            log.info("调用AI服务生成学习计划...");
            planText = callAIService(prompt);
            if (planText == null || planText.trim().isEmpty()) {
                throw new Exception("AI服务返回空响应");
            }
            log.info("AI服务返回成功，计划长度: {} 字符", planText.length());
        } catch (Exception e) {
            log.error("AI服务调用失败: {}", e.getMessage(), e);
            throw new Exception("AI服务调用失败: " + e.getMessage());
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

    /**
     * 构建智能提示词 - 根据参数是否为空动态调整
     */
    private String buildIntelligentPrompt(String title, String subject, String duration, String level) {
        StringBuilder prompt = new StringBuilder();

        // 角色定位
        prompt.append("你是一位经验丰富的大学教育规划专家。\n\n");

        // 学生信息（只添加非空的字段）
        prompt.append("【学生信息】\n");

        if (level != null && !level.trim().isEmpty()) {
            prompt.append("- 年级：").append(level).append("\n");
            prompt.append("  请根据该年级学生的普遍学习阶段制定计划\n");
        }

        if (subject != null && !subject.trim().isEmpty()) {
            prompt.append("- 学科/方向：").append(subject).append("\n");
        } else {
            prompt.append("- 学科/方向：请根据计划名称「").append(title).append("」合理推断\n");
        }

        if (duration != null && !duration.trim().isEmpty()) {
            prompt.append("- 计划时长：").append(duration).append("\n");
        } else {
            prompt.append("- 计划时长：请根据计划内容智能推荐合理时长\n");
        }

        // 计划名称
        prompt.append("\n【计划名称】\n");
        prompt.append(title).append("\n");

        // 制定要求
        prompt.append("""
                
                【制定要求】
                1. 输出格式：使用 Markdown 格式，层次清晰
                
                2. 计划内容必须包含：
                   - 学习目标：明确的学习成果
                   - 时间安排：详细的时间规划
                   - 具体学习内容：知识点或技能
                   - 学习资源推荐：书籍、课程、网站等
                   - 评估方式：如何检验学习效果
                
                3. 输出约束：
                   - 不要任何开场白（如"以下是为您制定的计划"、"您好"等）
                   - 不要任何结束语（如"希望对您有帮助"、"祝学习进步"等）
                   - 直接输出计划内容
                
                【学习计划】
                """);

        return prompt.toString();
    }

    /**
     * 调用AI服务
     */
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