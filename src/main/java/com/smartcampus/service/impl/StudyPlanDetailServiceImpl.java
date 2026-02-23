// src/main/java/com/smartcampus/service/impl/StudyPlanDetailServiceImpl.java
package com.smartcampus.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public Map<String, Object> createPlanDetailForUser(Long studyPlanId, String subject, String duration, String level) throws Exception {
        log.info("开始为学习计划 {} 生成详情: subject={}, duration={}, level={}", studyPlanId, subject, duration, level);

        // 1. 构建提示词
        String prompt = buildPrompt(subject, duration, level);

        // 2. 调用AI服务
        String planJsonString = callAIService(prompt);
        if (planJsonString == null || planJsonString.trim().isEmpty()) {
            log.error("AI服务返回空响应");
            throw new Exception("AI服务返回空响应");
        }

        // 3. 解析AI返回的JSON
        JsonNode planNode = parsePlanJson(planJsonString);
        if (planNode == null) {
            log.error("AI返回格式错误，无法解析: {}", planJsonString);
            throw new Exception("AI返回格式错误，无法解析");
        }

        // 4. 创建实体并保存到数据库 - 匹配新表结构
        StudyPlanDetail detail = new StudyPlanDetail();
        detail.setStudyPlanId(studyPlanId);  // 改为 studyPlanId
        detail.setDuration(duration);         // 单次学习时长
        detail.setLevel(level);               // 难度级别
        detail.setPlanDetails(planNode.toString()); // AI生成的计划内容
        detail.setCreatedAt(LocalDateTime.now());

        StudyPlanDetail savedDetail = studyPlanDetailRepository.save(detail);

        // 5. 准备返回数据
        Map<String, Object> result = new HashMap<>();
        result.put("planDetails", planNode);
        result.put("detailId", savedDetail.getId());
        result.put("studyPlanId", savedDetail.getStudyPlanId());

        log.info("计划详情生成并保存成功，detailId: {}, studyPlanId: {}",
                savedDetail.getId(), savedDetail.getStudyPlanId());
        return result;
    }

    // 其他方法保持不变...
    private String buildPrompt(String subject, String duration, String level) {
        return String.format(
                "请为%s级别的学生制定一份关于'%s'的详细学习计划，总时长为%s。计划应非常详细，包括每周甚至每天的具体学习任务、推荐的学习资源（书籍、网站、视频等）、练习建议以及阶段性的小测验或项目。请务必以标准的JSON格式返回，结构如下：\n" +
                        "{\n" +
                        "  \"plan\": [\n" +
                        "    {\n" +
                        "      \"week\": 1,\n" +
                        "      \"title\": \"Week 1: Introduction to ...\",\n" +
                        "      \"days\": [\n" +
                        "        {\n" +
                        "          \"day\": 1,\n" +
                        "          \"topic\": \"Basic Concepts\",\n" +
                        "          \"tasks\": [\"Read Chapter 1\", \"Watch Video 1.1\"],\n" +
                        "          \"resources\": [\"Book A\", \"Website B\"],\n" +
                        "          \"assignments\": [\"Exercise 1.1\"]\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}\n" +
                        "请确保JSON格式正确，可以直接被解析。",
                level, subject, duration
        );
    }

    private String callAIService(String prompt) throws Exception {
        try {
            log.info("正在调用AI服务...");
            return qianWenService.askQuestion(prompt, Collections.emptyList(), "qwen-max")
                    .block(java.time.Duration.ofSeconds(120));
        } catch (Exception e) {
            log.error("调用AI服务失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    private JsonNode parsePlanJson(String jsonString) throws Exception {
        try {
            String extractedJson = extractJsonFromText(jsonString);
            if(extractedJson == null){
                log.error("无法从AI响应中提取有效的JSON: {}", jsonString);
                return null;
            }
            return objectMapper.readTree(extractedJson);
        } catch (Exception e) {
            log.error("解析AI返回的JSON失败: {}", jsonString, e);
            throw e;
        }
    }

    private String extractJsonFromText(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            String jsonCandidate = text.substring(start, end + 1).trim();
            try {
                objectMapper.readTree(jsonCandidate);
                log.debug("成功提取并验证了JSON块");
                return jsonCandidate;
            } catch (Exception e) {
                log.debug("提取的候选JSON无效: {}", jsonCandidate);
            }
        }
        return null;
    }
}