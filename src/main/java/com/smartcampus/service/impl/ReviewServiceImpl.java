package com.smartcampus.service.impl;

import com.smartcampus.dao.StudyPlanDao;
import com.smartcampus.dto.*;
import com.smartcampus.entity.DifficultyMark;
import com.smartcampus.entity.ReviewRecord;
import com.smartcampus.entity.StudyPlan;
import com.smartcampus.exception.BusinessException;
import com.smartcampus.repository.DifficultyMarkRepository;
import com.smartcampus.repository.ReviewRecordRepository;
import com.smartcampus.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final StudyPlanDao studyPlanDao;
    private final DifficultyMarkRepository difficultyMarkRepository;
    private final ReviewRecordRepository reviewRecordRepository;

    @Override
    public ReviewPlan getReviewPlan(Integer userId, Long planId) {
        log.info("获取复习计划 - userId: {}, planId: {}", userId, planId);

        if (planId != null) {
            // 获取单个计划的复习计划
            StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                    .orElseThrow(() -> new BusinessException("计划不存在"));
            return generateReviewPlanForPlan(plan);
        } else {
            // 获取用户所有进行中计划的复习计划
            List<StudyPlan> activePlans = studyPlanDao.findByUserIdAndStatus(userId, "active");
            return generateReviewPlanForUser(activePlans);
        }
    }

    @Override
    @Transactional
    public ReviewCurve generateReviewCurve(Integer userId, GenerateReviewRequest request) {
        log.info("生成复习曲线 - userId: {}, planIds: {}, intensity: {}",
                userId, request.getPlanIds(), request.getReviewIntensity());

        // 验证计划是否存在且属于该用户
        List<StudyPlan> plans = new ArrayList<>();
        for (Long planId : request.getPlanIds()) {
            StudyPlan plan = studyPlanDao.findByIdAndUserId(planId, userId)
                    .orElseThrow(() -> new BusinessException("计划ID " + planId + " 不存在或无权限"));
            plans.add(plan);
        }

        // 基于艾宾浩斯遗忘曲线生成复习计划
        return generateEbbinghausCurve(plans, request.getReviewIntensity(), request.getDifficultyTags());
    }

    @Override
    @Transactional
    public DifficultyMark markDifficulty(Integer userId, MarkDifficultyRequest request) {
        log.info("标记难点 - userId: {}, planId: {}", userId, request.getPlanId());

        // 验证计划是否存在
        StudyPlan plan = studyPlanDao.findByIdAndUserId(request.getPlanId(), userId)
                .orElseThrow(() -> new BusinessException("计划不存在"));

        // 创建难点标记
        DifficultyMark mark = new DifficultyMark();
        mark.setUserId(userId);
        mark.setPlanId(request.getPlanId());
        mark.setContent(request.getContent());
        mark.setTags(request.getTags());

        return difficultyMarkRepository.save(mark);
    }

    @Override
    public List<DifficultyMark> getUserDifficultyMarks(Integer userId, Long planId) {
        if (planId != null) {
            return difficultyMarkRepository.findByUserIdAndPlanId(userId, planId);
        } else {
            return difficultyMarkRepository.findByUserId(userId);
        }
    }

    /**
     * 基于艾宾浩斯遗忘曲线生成复习计划
     */
    private ReviewCurve generateEbbinghausCurve(List<StudyPlan> plans, String intensity, List<String> difficultyTags) {
        ReviewCurve curve = new ReviewCurve();
        List<ReviewPoint> points = new ArrayList<>();

        // 艾宾浩斯复习时间点（天数）
        int[] reviewDays = {0, 1, 2, 4, 7, 15, 30};

        // 根据强度调整最大复习天数
        int maxDays = switch (intensity) {
            case "light" -> 7;      // 轻松：只复习到7天
            case "intense" -> 30;   //  intensive：复习到30天
            default -> 15;           // 默认：15天
        };

        LocalDateTime now = LocalDateTime.now();
        List<String> planTitles = plans.stream().map(StudyPlan::getTitle).collect(Collectors.toList());

        for (int day : reviewDays) {
            if (day > maxDays) continue;

            ReviewPoint point = new ReviewPoint();
            point.setDay(day);
            point.setReviewTime(now.plusDays(day));

            if (day == 0) {
                point.setDescription("首次学习：" + String.join("、", planTitles));
            } else {
                String desc = String.format("第%d次复习（艾宾浩斯曲线）", day);
                if (!difficultyTags.isEmpty()) {
                    desc += "，重点复习：" + String.join("、", difficultyTags);
                }
                point.setDescription(desc);
            }
            points.add(point);

            // 保存复习记录到数据库
            for (StudyPlan plan : plans) {
                ReviewRecord record = new ReviewRecord();
                record.setUserId(plan.getUserId());
                record.setPlanId(plan.getId().longValue());
                record.setReviewDay(day);
                record.setReviewTime(now.plusDays(day));
                record.setCompleted(false);
                reviewRecordRepository.save(record);
            }
        }

        curve.setPoints(points);
        curve.setRecommendation(generateRecommendation(plans, intensity, difficultyTags));

        return curve;
    }

    /**
     * 为单个计划生成复习计划
     */
    private ReviewPlan generateReviewPlanForPlan(StudyPlan plan) {
        ReviewPlan reviewPlan = new ReviewPlan();
        reviewPlan.setId(plan.getId().longValue());
        reviewPlan.setUserId(plan.getUserId().longValue());
        reviewPlan.setTitle(plan.getTitle() + " 复习计划");

        List<ReviewItem> items = new ArrayList<>();

        // 获取该计划的难点标记
        List<DifficultyMark> marks = difficultyMarkRepository.findByUserIdAndPlanId(
                plan.getUserId(), plan.getId().longValue());

        if (!marks.isEmpty()) {
            for (DifficultyMark mark : marks) {
                ReviewItem item = new ReviewItem();
                item.setContent("难点：" + mark.getContent());
                item.setDifficulty(plan.getDifficulty());
                item.setTags(mark.getTags());
                items.add(item);
            }
        } else {
            // 没有标记难点，使用计划描述
            ReviewItem item = new ReviewItem();
            item.setContent(plan.getDescription() != null ? plan.getDescription() : plan.getTitle());
            item.setDifficulty(plan.getDifficulty());
            items.add(item);
        }

        reviewPlan.setItems(items);
        reviewPlan.setCreatedAt(LocalDateTime.now());
        reviewPlan.setNextReviewTime(LocalDateTime.now().plusDays(1));

        return reviewPlan;
    }

    /**
     * 为用户生成综合复习计划
     */
    private ReviewPlan generateReviewPlanForUser(List<StudyPlan> plans) {
        ReviewPlan reviewPlan = new ReviewPlan();
        reviewPlan.setUserId(plans.get(0).getUserId().longValue());
        reviewPlan.setTitle("今日复习计划");

        List<ReviewItem> items = new ArrayList<>();

        // 获取今天需要复习的计划
        LocalDateTime now = LocalDateTime.now();
        List<ReviewRecord> todayReviews = reviewRecordRepository.findByUserIdAndCompletedFalse(
                plans.get(0).getUserId());

        for (ReviewRecord record : todayReviews) {
            plans.stream()
                    .filter(p -> p.getId().equals(record.getPlanId()))
                    .findFirst()
                    .ifPresent(plan -> {
                        ReviewItem item = new ReviewItem();
                        item.setContent(plan.getTitle());
                        item.setDifficulty(plan.getDifficulty());
                        item.setReviewDay(record.getReviewDay());
                        items.add(item);
                    });
        }

        reviewPlan.setItems(items);
        reviewPlan.setCreatedAt(LocalDateTime.now());
        reviewPlan.setNextReviewTime(LocalDateTime.now().plusDays(1));

        return reviewPlan;
    }

    /**
     * 生成建议文本
     */
    private String generateRecommendation(List<StudyPlan> plans, String intensity, List<String> difficultyTags) {
        StringBuilder sb = new StringBuilder();

        sb.append("📚 根据艾宾浩斯遗忘曲线，为您生成复习计划：\n\n");

        if ("intense".equals(intensity)) {
            sb.append("🔴 强度：强效记忆模式\n");
            sb.append("⏰ 复习时间点：1天、2天、4天、7天、15天、30天后\n");
            sb.append("💪 适合需要长期记忆的重要内容\n");
        } else if ("light".equals(intensity)) {
            sb.append("🟢 强度：轻松复习模式\n");
            sb.append("⏰ 复习时间点：1天、2天、4天、7天后\n");
            sb.append("😊 适合快速掌握的基础内容\n");
        } else {
            sb.append("🟡 强度：标准复习模式\n");
            sb.append("⏰ 复习时间点：1天、2天、4天、7天、15天后\n");
            sb.append("📊 平衡记忆效果与复习负担\n");
        }

        sb.append("\n📋 本次复习包含 ").append(plans.size()).append(" 个学习计划：\n");
        for (StudyPlan plan : plans) {
            sb.append("  • ").append(plan.getTitle());
            if (plan.getSubject() != null) {
                sb.append(" (").append(plan.getSubject()).append(")");
            }
            sb.append("\n");
        }

        if (!difficultyTags.isEmpty()) {
            sb.append("\n⚠️ 重点关注难点：\n");
            for (String tag : difficultyTags) {
                sb.append("  • ").append(tag).append("\n");
            }
        }

        sb.append("\n✨ 建议：");
        sb.append("\n1. 按时复习，不要堆积");
        sb.append("\n2. 每次复习后标记难点，系统会自动强化");
        sb.append("\n3. 可以根据掌握程度调整复习强度");

        return sb.toString();
    }
}