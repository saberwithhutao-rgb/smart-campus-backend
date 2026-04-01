package com.smartcampus.service.impl;

import com.smartcampus.dao.StudyPlanDao;
import com.smartcampus.dto.CreatePlanRequest;
import com.smartcampus.dto.UpdatePlanRequest;
import com.smartcampus.dto.PageResult;
import com.smartcampus.entity.StudyPlan;
import com.smartcampus.exception.BusinessException;
import com.smartcampus.service.StudyPlanService;
import com.smartcampus.service.StudyTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyPlanServiceImpl implements StudyPlanService {

    private final StudyPlanDao studyPlanDao;
    private final StudyTaskService studyTaskService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * 校验日期格式
     */
    private void validateDateFormat(String date, String fieldName) {
        try {
            if (date != null) {
                LocalDate.parse(date, DATE_FORMATTER);
            }
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, fieldName + "格式必须是 yyyy-MM-dd");
        }
    }

    /**
     * 校验开始日期不能早于今天
     */
    private void validateStartDateNotBeforeToday(LocalDate startDate) {
        if (startDate != null && startDate.isBefore(LocalDate.now())) {
            throw new BusinessException(400, "开始日期不能早于今天");
        }
    }

    /**
     * 校验结束日期不能早于开始日期
     */
    private void validateEndDateNotBeforeStartDate(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
    }

    @Override
    public PageResult<StudyPlan> getPlans(Integer userId, Integer page, Integer size,
                                          String status, String planType, String subject) {
        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }

        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<StudyPlan> planPage;

        if (StringUtils.hasText(status) && StringUtils.hasText(planType) && StringUtils.hasText(subject)) {
            planPage = studyPlanDao.findByUserIdAndStatusAndPlanTypeAndSubjectContaining(
                    userId, status, planType, subject, pageable);
        } else if (StringUtils.hasText(status)) {
            planPage = studyPlanDao.findByUserIdAndStatus(userId, status, pageable);
        } else if (StringUtils.hasText(planType)) {
            planPage = studyPlanDao.findByUserIdAndPlanType(userId, planType, pageable);
        } else if (StringUtils.hasText(subject)) {
            planPage = studyPlanDao.findByUserIdAndSubjectLike(userId, subject, pageable);
        } else {
            planPage = studyPlanDao.findByUserId(userId, pageable);
        }

        return new PageResult<>(
                planPage.getContent(),
                planPage.getTotalElements(),
                planPage.getNumber() + 1,
                planPage.getSize(),
                planPage.getTotalPages()
        );
    }

    @Override
    @Transactional
    public StudyPlan createPlan(Integer userId, CreatePlanRequest request) {
        log.info("创建学习计划 - userId: {}", userId);

        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }

        // 1. 校验必填字段
        if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            throw new BusinessException(400, "计划名称不能为空");
        }
        if (request.getPlanType() == null) {
            throw new BusinessException(400, "计划类型不能为空");
        }

        // 2. 校验日期格式
        if (request.getStartDate() == null) {
            throw new BusinessException(400, "开始日期不能为空");
        }
        validateDateFormat(request.getStartDate().toString(), "开始日期");
        if (request.getEndDate() != null) {
            validateDateFormat(request.getEndDate().toString(), "结束日期");
        }

        // 3. 开始日期不能早于今天
        validateStartDateNotBeforeToday(request.getStartDate());

        // 4. 结束日期不能早于开始日期
        validateEndDateNotBeforeStartDate(request.getStartDate(), request.getEndDate());

        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(request.getTitle().trim());
        plan.setDescription(request.getDescription());
        plan.setPlanType(request.getPlanType());
        plan.setSubject(request.getSubject());
        plan.setDifficulty(request.getDifficulty() != null ? request.getDifficulty() : "medium");
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setStatus("active");

        StudyPlan saved = studyPlanDao.save(plan);
        log.info("学习计划创建成功 - id: {}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public StudyPlan updatePlan(Integer userId, Integer planId, UpdatePlanRequest request) {
        log.info("更新学习计划 - userId: {}, planId: {}", userId, planId);

        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }

        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在或无权限修改"));

        // ==================== 1. 已完成计划处理 ====================
        if ("completed".equals(plan.getStatus())) {
            if (request.getTitle() != null || request.getDescription() != null ||
                    request.getPlanType() != null || request.getSubject() != null ||
                    request.getDifficulty() != null || request.getStartDate() != null ||
                    request.getEndDate() != null || request.getStatus() != null) {
                throw new BusinessException(403, "已完成计划不能修改任何信息");
            }
            return plan;
        }

        // ==================== 2. 开始日期校验 ====================
        // ✅ 核心规则：开始日期永久不允许修改
        if (request.getStartDate() != null && !request.getStartDate().equals(plan.getStartDate())) {
            throw new BusinessException(403, "开始日期创建后不允许修改");
        }

        // ==================== 3. 其他字段校验 ====================

        // 结束日期校验
        if (request.getEndDate() != null) {
            validateDateFormat(request.getEndDate().toString(), "结束日期");

            // 进行中的计划：结束日期只能延后，不能提前
            if ("active".equals(plan.getStatus()) && plan.getEndDate() != null) {
                if (request.getEndDate().isBefore(plan.getEndDate())) {
                    throw new BusinessException(403, "进行中的计划不能将结束日期提前");
                }
            }
        }

        // 状态变更校验
        if (StringUtils.hasText(request.getStatus())) {
            // 不允许将已完成状态改为其他
            if ("completed".equals(plan.getStatus()) && !"completed".equals(request.getStatus())) {
                throw new BusinessException(403, "已完成计划不能改变状态");
            }

            // 未开始的计划可以改为进行中或完成
            if ("paused".equals(plan.getStatus())) {
                if (!"active".equals(request.getStatus()) && !"completed".equals(request.getStatus())) {
                    throw new BusinessException(400, "未开始的计划只能改为进行中或完成");
                }
            }
        }

        // ==================== 4. 更新字段 ====================

        if (StringUtils.hasText(request.getTitle())) {
            plan.setTitle(request.getTitle().trim());
        }

        if (request.getDescription() != null) {
            plan.setDescription(request.getDescription());
        }

        if (StringUtils.hasText(request.getPlanType())) {
            plan.setPlanType(request.getPlanType());
        }

        if (request.getSubject() != null) {
            plan.setSubject(request.getSubject());
        }

        if (StringUtils.hasText(request.getDifficulty())) {
            plan.setDifficulty(request.getDifficulty());
        }

        // 结束日期（进行中的计划只能延后）
        if (request.getEndDate() != null) {
            plan.setEndDate(request.getEndDate());
        }

        if (StringUtils.hasText(request.getStatus())) {
            plan.setStatus(request.getStatus());
        }

        return studyPlanDao.save(plan);
    }

    @Override
    @Transactional
    public void deletePlan(Integer userId, Integer planId) {
        log.info("删除学习计划 - userId: {}, planId: {}", userId, planId);

        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }

        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在或无权限删除"));

        studyTaskService.deleteTasksByPlanId(planId);
        log.info("已删除计划关联的复习任务 - planId: {}", planId);

        studyPlanDao.delete(plan);
        log.info("学习计划删除成功 - id: {}", planId);
    }

    @Override
    public StudyPlan getPlanById(Integer userId, Integer planId) {
        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }
        return studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在"));
    }

    @Override
    public List<StudyPlan> getSchedule(Integer userId, Integer planId,
                                       LocalDate startDate, LocalDate endDate) {
        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }
        return studyPlanDao.findSchedule(userId, planId, startDate, endDate);
    }

    @Override
    @Transactional
    public StudyPlan toggleComplete(Integer userId, Integer planId) {
        log.info("切换计划完成状态 - userId: {}, planId: {}", userId, planId);

        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }

        StudyPlan plan = getPlanById(userId, planId);

        // 已完成不能重新激活
        if ("completed".equals(plan.getStatus())) {
            throw new BusinessException(403, "已完成计划不能重新激活，请创建新计划");
        }

        plan.setStatus("completed");
        log.info("计划从未完成变为完成，直接生成第一次复习任务");

        // ✅ 修改：直接创建第一次复习任务（reviewStage = 1）
        CompletableFuture.runAsync(() -> {
            try {
                // 调用新方法直接创建第一次复习任务
                studyTaskService.createFirstReviewTaskFromPlan(plan);
                log.info("第一次复习任务生成成功");
            } catch (Exception e) {
                log.error("第一次复习任务生成失败", e);
            }
        });

        return studyPlanDao.save(plan);
    }
}