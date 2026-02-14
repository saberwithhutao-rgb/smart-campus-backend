package com.smartcampus.service;

import com.smartcampus.dao.StudyPlanDao;
import com.smartcampus.dto.CreatePlanRequest;
import com.smartcampus.dto.UpdatePlanRequest;
import com.smartcampus.dto.UpdateProgressRequest;
import com.smartcampus.dto.PageResult;
import com.smartcampus.entity.StudyPlan;
import com.smartcampus.exception.BusinessException;
import com.smartcampus.service.StudyPlanService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyPlanServiceImpl implements StudyPlanService {

    private final StudyPlanDao studyPlanDao;
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
            throw new BusinessException(fieldName + "格式必须是 yyyy-MM-dd");
        }
    }

    /**
     * 校验开始日期不能早于今天
     */
    private void validateStartDateNotBeforeToday(LocalDate startDate) {
        if (startDate != null && startDate.isBefore(LocalDate.now())) {
            throw new BusinessException("开始日期不能早于今天");
        }
    }

    /**
     * 校验结束日期不能早于开始日期
     */
    private void validateEndDateNotBeforeStartDate(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
    }

    @Override
    public PageResult<StudyPlan> getPlans(Integer userId, Integer page, Integer size,
                                          String status, String planType, String subject) {
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
        System.out.println("🔥 createPlan - userId: " + userId);

        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }

        // ✅ 1. 校验日期格式
        validateDateFormat(request.getStartDate().toString(), "开始日期");
        if (request.getEndDate() != null) {
            validateDateFormat(request.getEndDate().toString(), "结束日期");
        }

        // ✅ 2. 开始日期不能为空，不能早于今天
        if (request.getStartDate() == null) {
            throw new BusinessException("开始日期不能为空");
        }
        validateStartDateNotBeforeToday(request.getStartDate());

        // ✅ 3. 结束日期不能早于开始日期
        validateEndDateNotBeforeStartDate(request.getStartDate(), request.getEndDate());

        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(request.getTitle());
        plan.setDescription(request.getDescription());
        plan.setPlanType(request.getPlanType());
        plan.setSubject(request.getSubject());
        plan.setDifficulty(request.getDifficulty());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setProgressPercent(request.getProgressPercent() != null ? request.getProgressPercent() : 0);
        plan.setStatus("active");

        return studyPlanDao.save(plan);
    }

    @Override
    @Transactional
    public StudyPlan updatePlan(Integer userId, Integer planId, UpdatePlanRequest request) {
        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException("计划不存在或无权限修改"));

        // ✅ 4. 计划已完成不能修改任何日期，不能降低进度
        if ("completed".equals(plan.getStatus())) {
            // 检查是否尝试修改任何字段
            if (request.getTitle() != null || request.getDescription() != null ||
                    request.getPlanType() != null || request.getSubject() != null ||
                    request.getDifficulty() != null || request.getStartDate() != null ||
                    request.getEndDate() != null || request.getProgressPercent() != null) {
                throw new BusinessException("已完成计划不能修改任何信息");
            }
            return plan;
        }

        // ✅ 5. 计划进行中不能修改开始日期
        if ("active".equals(plan.getStatus()) && request.getStartDate() != null) {
            if (!request.getStartDate().equals(plan.getStartDate())) {
                throw new BusinessException("进行中的计划不能修改开始日期");
            }
        }

        // ✅ 6. 校验日期格式
        if (request.getStartDate() != null) {
            validateDateFormat(request.getStartDate().toString(), "开始日期");
        }
        if (request.getEndDate() != null) {
            validateDateFormat(request.getEndDate().toString(), "结束日期");
        }

        // ✅ 7. 如果修改开始日期，不能早于今天
        if (request.getStartDate() != null) {
            validateStartDateNotBeforeToday(request.getStartDate());
        }

        // ✅ 8. 结束日期不能早于开始日期（使用最新的日期）
        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : plan.getStartDate();
        LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : plan.getEndDate();
        validateEndDateNotBeforeStartDate(startDate, endDate);

        // 更新字段
        if (StringUtils.hasText(request.getTitle())) {
            plan.setTitle(request.getTitle());
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
            // ✅ 不能降低进度
            if (request.getProgressPercent() < plan.getProgressPercent()) {
                throw new BusinessException("不能降低学习进度");
            }
            plan.setProgressPercent(request.getProgressPercent());
            // 进度100%自动完成
            if (request.getProgressPercent() >= 100) {
                plan.setStatus("completed");
            }
        }
        if (request.getStartDate() != null) {
            plan.setStartDate(request.getStartDate());
        }
        if (request.getEndDate() != null) {
            plan.setEndDate(request.getEndDate());
        }

        return studyPlanDao.save(plan);
    }

    @Override
    @Transactional
    public void deletePlan(Integer userId, Integer planId) {
        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException("计划不存在或无权限删除"));

        // ✅ 计划已完成不能删除
        if ("completed".equals(plan.getStatus())) {
            throw new BusinessException("已完成计划不能删除");
        }

        studyPlanDao.delete(plan);
    }

    @Override
    @Transactional
    public StudyPlan updateProgress(Integer userId, Integer planId, UpdateProgressRequest request) {
        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException("计划不存在或无权限修改"));

        Short progress = request.getProgressPercent();

        // ✅ 不能降低进度
        if (progress < plan.getProgressPercent()) {
            throw new BusinessException("不能降低学习进度");
        }

        // ✅ 已完成计划不能修改进度
        if ("completed".equals(plan.getStatus())) {
            throw new BusinessException("已完成计划不能修改进度");
        }

        int updated = studyPlanDao.updateProgress(planId, userId, progress);
        if (updated == 0) {
            throw new BusinessException("计划不存在或无权限修改");
        }

        return studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException("计划不存在"));
    }

    @Override
    public StudyPlan getPlanById(Integer userId, Integer planId) {
        return studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException("计划不存在"));
    }

    @Override
    public List<StudyPlan> getSchedule(Integer userId, Integer planId,
                                       LocalDate startDate, LocalDate endDate) {
        return studyPlanDao.findSchedule(userId, planId, startDate, endDate);
    }

    @Override
    @Transactional
    public StudyPlan toggleComplete(Integer userId, Integer planId) {
        StudyPlan plan = getPlanById(userId, planId);

        // ✅ 已完成不能重新激活
        if ("completed".equals(plan.getStatus())) {
            throw new BusinessException("已完成计划不能重新激活");
        }

        Short newProgress = (short) (plan.getProgressPercent() >= 100 ? 0 : 100);
        UpdateProgressRequest request = new UpdateProgressRequest();
        request.setProgressPercent(newProgress);

        return updateProgress(userId, planId, request);
    }
}