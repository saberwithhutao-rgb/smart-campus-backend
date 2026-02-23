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
        detail.setStudyPlanId(studyPlanId.intValue());  // 改为 studyPlanId
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
                "请为%s级别的学生制定一份关于'%s'的详细学习计划，总时长为%s。\n\n" +
                        "## 重要要求：\n" +
                        "1. 必须按周（week）组织计划\n" +
                        "2. 每周内按天（days）细分\n" +
                        "3. 不要使用sessions字段，直接把任务放在days层级\n" +
                        "4. 返回纯JSON，不要包含任何其他文字说明\n\n" +
                        "## 必须严格遵守的JSON格式：\n" +
                        "{\n" +
                        "  \"plan\": [\n" +
                        "    {\n" +
                        "      \"week\": 1,\n" +
                        "      \"title\": \"第1周：xxx\",\n" +
                        "      \"days\": [\n" +
                        "        {\n" +
                        "          \"day\": 1,\n" +
                        "          \"topic\": \"学习主题\",\n" +
                        "          \"tasks\": [\"任务1\", \"任务2\"],\n" +
                        "          \"resources\": [\"资源1\", \"资源2\"],\n" +
                        "          \"assignments\": [\"作业1\"]\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}\n\n" +
                        "## 示例（请严格按照这个结构）：\n" +
                        "{\n" +
                        "  \"plan\": [\n" +
                        "    {\n" +
                        "      \"week\": 1,\n" +
                        "      \"title\": \"第1周：HTML和CSS基础\",\n" +
                        "      \"days\": [\n" +
                        "        {\n" +
                        "          \"day\": 1,\n" +
                        "          \"topic\": \"HTML入门\",\n" +
                        "          \"tasks\": [\"学习HTML基本标签\", \"创建第一个网页\"],\n" +
                        "          \"resources\": [\"MDN Web Docs - HTML教程\", \"W3Schools - HTML基础\"],\n" +
                        "          \"assignments\": [\"制作个人简介页面\"]\n" +
                        "        },\n" +
                        "        {\n" +
                        "          \"day\": 2,\n" +
                        "          \"topic\": \"CSS基础\",\n" +
                        "          \"tasks\": [\"学习CSS选择器\", \"掌握盒模型\"],\n" +
                        "          \"resources\": [\"CSS Tricks - 盒模型指南\", \"W3Schools - CSS教程\"],\n" +
                        "          \"assignments\": [\"美化个人简介页面\"]\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    },\n" +
                        "    {\n" +
                        "      \"week\": 2,\n" +
                        "      \"title\": \"第2周：JavaScript基础\",\n" +
                        "      \"days\": [\n" +
                        "        {\n" +
                        "          \"day\": 8,\n" +
                        "          \"topic\": \"JavaScript语法\",\n" +
                        "          \"tasks\": [\"学习变量和数据类型\", \"掌握运算符\"],\n" +
                        "          \"resources\": [\"JavaScript.info - 基础知识\", \"MDN - JavaScript指南\"],\n" +
                        "          \"assignments\": [\"编写简单的计算器程序\"]\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}\n\n" +
                        "请确保：\n" +
                        "1. 不要添加任何额外字段（如sessions）\n" +
                        "2. 严格按照示例格式返回\n" +
                        "3. 使用中文回复\n" +
                        "4. 确保JSON格式正确，可直接解析",
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