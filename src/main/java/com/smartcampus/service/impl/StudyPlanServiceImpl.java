package com.smartcampus.service.impl;

import com.smartcampus.dao.StudyPlanDao;
import com.smartcampus.dto.CreatePlanRequest;
import com.smartcampus.dto.UpdatePlanRequest;
import com.smartcampus.dto.UpdateProgressRequest;
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
        plan.setProgressPercent(request.getProgressPercent() != null ? request.getProgressPercent() : 0);
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

        // 已完成计划不能修改
        if ("completed".equals(plan.getStatus())) {
            if (request.getTitle() != null || request.getDescription() != null ||
                    request.getPlanType() != null || request.getSubject() != null ||
                    request.getDifficulty() != null || request.getStartDate() != null ||
                    request.getEndDate() != null || request.getProgressPercent() != null ||
                    request.getStatus() != null) {
                throw new BusinessException(403, "已完成计划不能修改任何信息");
            }
            return plan;
        }

        // 进行中的计划不能修改开始日期
        if ("active".equals(plan.getStatus()) && request.getStartDate() != null) {
            if (!request.getStartDate().equals(plan.getStartDate())) {
                throw new BusinessException(403, "进行中的计划不能修改开始日期");
            }
        }

        // 校验日期格式
        if (request.getStartDate() != null) {
            validateDateFormat(request.getStartDate().toString(), "开始日期");
        }
        if (request.getEndDate() != null) {
            validateDateFormat(request.getEndDate().toString(), "结束日期");
        }

        // 如果修改开始日期，不能早于今天
        if (request.getStartDate() != null) {
            validateStartDateNotBeforeToday(request.getStartDate());
        }

        // 结束日期不能早于开始日期
        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : plan.getStartDate();
        LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : plan.getEndDate();
        validateEndDateNotBeforeStartDate(startDate, endDate);

        // 更新字段
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
        if (request.getProgressPercent() != null) {
            // 不能降低进度
            if (request.getProgressPercent() < plan.getProgressPercent()) {
                throw new BusinessException(400, "不能降低学习进度");
            }
            plan.setProgressPercent(request.getProgressPercent());

            if (request.getProgressPercent() >= 100) {
                plan.setStatus("completed");
                log.info("计划进度100%，自动标记为完成 - planId: {}", planId);

                // ✅ 只生成第一次复习任务！
                CompletableFuture.runAsync(() -> {
                    studyTaskService.createFirstReviewTask(plan);
                });
            }
        }
        if (request.getStartDate() != null) {
            plan.setStartDate(request.getStartDate());
        }
        if (request.getEndDate() != null) {
            plan.setEndDate(request.getEndDate());
        }
        if (StringUtils.hasText(request.getStatus())) {
            // 不能手动修改为已完成，只能通过进度触发
            if ("completed".equals(request.getStatus())) {
                throw new BusinessException(400, "不能手动标记为已完成，请将进度设置为100%");
            }
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

        // 已完成计划不能删除
        if ("completed".equals(plan.getStatus())) {
            throw new BusinessException(403, "已完成计划不能删除");
        }

        studyPlanDao.delete(plan);
        log.info("学习计划删除成功 - id: {}", planId);
    }

    @Override
    @Transactional
    public StudyPlan updateProgress(Integer userId, Integer planId, UpdateProgressRequest request) {
        log.info("更新学习进度 - userId: {}, planId: {}, progress: {}", userId, planId, request.getProgressPercent());

        if (userId == null) {
            throw new BusinessException(401, "用户未登录");
        }

        if (request.getProgressPercent() == null) {
            throw new BusinessException(400, "进度不能为空");
        }

        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在或无权限修改"));

        // 已完成计划不能修改进度
        if ("completed".equals(plan.getStatus())) {
            throw new BusinessException(403, "已完成计划不能修改进度");
        }

        Short progress = request.getProgressPercent();

        // 进度范围校验
        if (progress < 0 || progress > 100) {
            throw new BusinessException(400, "进度必须在0-100之间");
        }

        // 不能降低进度
        if (progress < plan.getProgressPercent()) {
            throw new BusinessException(400, "不能降低学习进度");
        }

        int updated = studyPlanDao.updateProgress(planId, userId, progress);
        if (updated == 0) {
            throw new BusinessException(500, "更新进度失败");
        }

        StudyPlan updatedPlan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在"));


        log.info("学习进度更新成功 - id: {}, newProgress: {}", planId, updatedPlan.getProgressPercent());
        return updatedPlan;
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

        Short newProgress = (short) (plan.getProgressPercent() >= 100 ? 0 : 100);

        UpdateProgressRequest request = new UpdateProgressRequest();
        request.setProgressPercent(newProgress);

        return updateProgress(userId, planId, request);
    }
}