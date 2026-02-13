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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyPlanServiceImpl implements StudyPlanService {

    private final StudyPlanDao studyPlanDao;

    @Override
    public PageResult<StudyPlan> getPlans(Integer userId, Integer page, Integer size,
                                          String status, String planType, String subject) {
        // 构建分页参数
        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<StudyPlan> planPage;

        // 动态查询
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
        // 参数校验
        System.out.println("🔥 createPlan - userId: " + userId);

        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }

        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new BusinessException("结束日期不能早于开始日期");
        }

        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(request.getTitle());
        plan.setDescription(request.getDescription());
        plan.setPlanType(request.getPlanType());
        plan.setSubject(request.getSubject());
        plan.setDifficulty(request.getDifficulty());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setProgressPercent(request.getProgressPercent());
        plan.setStatus("active");

        return studyPlanDao.save(plan);
    }

    @Override
    @Transactional
    public StudyPlan updatePlan(Integer userId, Integer planId, UpdatePlanRequest request) {
        // 查询计划并验证权限
        StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException("计划不存在或无权限修改"));

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
        if (StringUtils.hasText(request.getStatus())) {
            plan.setStatus(request.getStatus());
        }
        if (request.getProgressPercent() != null) {
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

        // 验证日期
        if (plan.getEndDate() != null && plan.getEndDate().isBefore(plan.getStartDate())) {
            throw new BusinessException("结束日期不能早于开始日期");
        }

        return studyPlanDao.save(plan);
    }

    @Override
    @Transactional
    public void deletePlan(Integer userId, Integer planId) {
        int deleted = studyPlanDao.deleteByIdAndUserId(planId, userId);
        if (deleted == 0) {
            throw new BusinessException("计划不存在或无权限删除");
        }
    }

    @Override
    @Transactional
    public StudyPlan updateProgress(Integer userId, Integer planId, UpdateProgressRequest request) {
        Short progress = request.getProgressPercent();

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

        Short newProgress = (short) (plan.getProgressPercent() >= 100 ? 0 : 100);
        UpdateProgressRequest request = new UpdateProgressRequest();
        request.setProgressPercent(newProgress);

        return updateProgress(userId, planId, request);
    }
}