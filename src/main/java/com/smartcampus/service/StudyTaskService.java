package com.smartcampus.service;

import com.smartcampus.dao.StudyTaskDao;
import com.smartcampus.entity.StudyPlan;
import com.smartcampus.entity.StudyTask;
import com.smartcampus.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyTaskService {

    private final StudyTaskDao studyTaskDao;

    /**
     * 生成复习任务（当计划完成时）
     * 使用艾宾浩斯遗忘曲线：1天、3天、7天、15天、30天
     */
    @Transactional
    public List<StudyTask> generateReviewTasks(StudyPlan plan) {
        List<StudyTask> tasks = new ArrayList<>();
        Integer userId = plan.getUserId();
        Integer planId = plan.getId();
        LocalDate startDate = plan.getEndDate() != null ? plan.getEndDate() : LocalDate.now();

        // 艾宾浩斯遗忘曲线复习间隔
        int[] intervals = {1, 3, 7, 15, 30};

        for (int i = 0; i < intervals.length; i++) {
            StudyTask task = new StudyTask();
            task.setPlanId(planId);
            task.setUserId(userId);
            task.setTitle(plan.getTitle() + " - 第" + (i + 1) + "次复习");
            task.setDescription(plan.getDescription());
            task.setTaskDate(startDate.plusDays(intervals[i]));
            task.setScheduledTime(LocalTime.of(20, 0)); // 默认晚上8点
            task.setDurationMinutes(30);
            task.setStatus("pending");
            task.setReviewStage((short) (i + 1));
            task.setCreatedAt(LocalDateTime.now());

            tasks.add(studyTaskDao.save(task));
        }

        log.info("为计划 {} 生成了 {} 个复习任务", planId, tasks.size());
        return tasks;
    }

    /**
     * 获取用户的复习任务
     */
    public List<StudyTask> getUserReviewTasks(Integer userId) {
        return studyTaskDao.findByUserIdAndStatusOrderByTaskDateAsc(userId, "pending");
    }

    /**
     * 获取今天的复习任务
     */
    public List<StudyTask> getTodayTasks(Integer userId) {
        return studyTaskDao.findByUserIdAndTaskDate(userId, LocalDate.now());
    }

    /**
     * 获取逾期未完成的任务
     */
    public List<StudyTask> getOverdueTasks(Integer userId) {
        return studyTaskDao.findByUserIdAndTaskDateLessThanEqualAndStatus(
                userId, LocalDate.now(), "pending");
    }

    /**
     * 完成任务
     */
    @Transactional
    public StudyTask completeTask(Integer userId, Integer taskId) {
        StudyTask task = studyTaskDao.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此任务");
        }

        if ("completed".equals(task.getStatus())) {
            throw new BusinessException("任务已完成");
        }

        task.setStatus("completed");
        task.setCompletedAt(LocalDateTime.now());

        return studyTaskDao.save(task);
    }

    /**
     * 删除计划相关的所有任务
     */
    @Transactional
    public void deleteTasksByPlanId(Integer planId) {
        studyTaskDao.deleteByPlanId(planId);
    }
}