package com.smartcampus.service;

import com.smartcampus.dto.CreatePlanRequest;
import com.smartcampus.dto.UpdatePlanRequest;
import com.smartcampus.dto.PageResult;
import com.smartcampus.entity.StudyPlan;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface StudyPlanService {

    // 分页查询计划列表
    PageResult<StudyPlan> getPlans(Integer userId, Integer page, Integer size,
                                   String status, String planType, String subject);

    // 创建计划
    StudyPlan createPlan(Integer userId, CreatePlanRequest request);

    // 更新计划
    StudyPlan updatePlan(Integer userId, Integer planId, UpdatePlanRequest request);

    // 删除计划
    void deletePlan(Integer userId, Integer planId);

    // 获取计划详情
    StudyPlan getPlanById(Integer userId, Integer planId);

    // 获取学习日程
    List<StudyPlan> getSchedule(Integer userId, Integer planId,
                                LocalDate startDate, LocalDate endDate);

    // 切换完成状态（便捷方法）
    StudyPlan toggleComplete(Integer userId, Integer planId);
}