// src/main/java/com/smartcampus/service/StudyPlanDetailService.java
package com.smartcampus.service;

import java.util.Map;

public interface StudyPlanDetailService {

    /**
     * 为学习计划生成并保存详情
     * @param studyPlanId 学习计划ID
     * @param subject 学习主题
     * @param duration 计划时长
     * @param level 难度级别
     * @return 包含plan(纯文本)和detailId的Map
     * @throws Exception 生成过程中可能出现的异常
     */
    Map<String, Object> createPlanDetailForUser(Long studyPlanId, String subject, String duration, String level) throws Exception;
}