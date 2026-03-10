package com.smartcampus.service;

import com.smartcampus.dao.StudyTaskDao;
import com.smartcampus.dao.StudyPlanDao;
import com.smartcampus.entity.StudyPlan;
import com.smartcampus.entity.StudyTask;
import com.smartcampus.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyTaskService {

    private final StudyTaskDao studyTaskDao;
    private final StudyPlanDao studyPlanDao;

    // 复习间隔（天数）
    private static final int[] REVIEW_INTERVALS = {1, 3, 7, 15, 30};


    /**
     * 创建第一次复习任务（当用户生成复习计划时）
     */
    @Transactional
    public void createFirstReviewTask(StudyTask initialTask) {
        StudyTask firstTask = new StudyTask();
        firstTask.setPlanId(initialTask.getPlanId());
        firstTask.setUserId(initialTask.getUserId());
        firstTask.setTitle(initialTask.getTitle());
        firstTask.setDescription(initialTask.getDescription());
        firstTask.setTaskDate(LocalDate.now().plusDays(1));  // 明天
        firstTask.setScheduledTime(null);
        firstTask.setDurationMinutes(30);
        firstTask.setStatus("pending");
        firstTask.setReviewStage((short) 1);  // ✅ 第1次复习
        firstTask.setCreatedAt(LocalDateTime.now());

        studyTaskDao.save(firstTask);
    }

    /**
     * 创建下一次复习任务（当完成当前任务时）
     */
    @Transactional
    public void createNextReviewTask(StudyTask currentTask) {
        int currentStage = currentTask.getReviewStage();

        if (currentStage >= 5) {
            log.info("计划 {} 已完成所有5次复习", currentTask.getPlanId());
            return;
        }

        int nextStage = currentStage + 1;

        LocalDate nextDate;
        nextDate = LocalDate.now().plusDays(REVIEW_INTERVALS[nextStage - 1]);

        StudyTask nextTask = new StudyTask();
        nextTask.setPlanId(currentTask.getPlanId());
        nextTask.setUserId(currentTask.getUserId());
        nextTask.setTitle(currentTask.getTitle());
        nextTask.setDescription(currentTask.getDescription());
        nextTask.setTaskDate(nextDate);
        nextTask.setScheduledTime(null);
        nextTask.setDurationMinutes(30);
        nextTask.setStatus("pending");
        nextTask.setReviewStage((short) nextStage);
        nextTask.setCreatedAt(LocalDateTime.now());

        studyTaskDao.save(nextTask);
    }

    /**
     * 完成任务（普通复习任务）
     */
    @Transactional
    public void completeReviewTask(Integer userId, Integer taskId) {
        StudyTask task = studyTaskDao.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作此任务");
        }

        // 创建下一次复习任务
        createNextReviewTask(task);

        // 删除当前任务
        studyTaskDao.delete(task);

        log.info("复习任务 {} 已完成", taskId);
    }

    /**
     * 获取用户的待复习任务
     */
    public List<StudyTask> getPendingTasks(Integer userId) {
        return studyTaskDao.findByUserIdAndStatusOrderByTaskDateAsc(userId, "pending");
    }

    /**
     * 获取今天的复习任务
     */
    public List<StudyTask> getTodayTasks(Integer userId) {
        return studyTaskDao.findByUserIdAndTaskDateAndStatus(
                userId, LocalDate.now(), "pending");
    }

    /**
     * 获取逾期任务
     */
    public List<StudyTask> getOverdueTasks(Integer userId) {
        return studyTaskDao.findByUserIdAndTaskDateLessThanAndStatus(
                userId, LocalDate.now(), "pending");
    }

    /**
     * 删除计划相关的所有任务
     */
    @Transactional
    public void deleteTasksByPlanId(Integer planId) {
        studyTaskDao.deleteByPlanId(planId);
    }

    /**
     * 获取用户所有任务（包括已完成）
     */
    public List<StudyTask> getAllTasks(Integer userId) {
        // 注意：您可能需要先在 StudyTaskDao 中添加这个方法
        return studyTaskDao.findByUserIdOrderByTaskDateDesc(userId);
    }

    /**
     * 根据ID获取任务（带权限验证）
     */
    public StudyTask getTaskById(Integer userId, Integer taskId) {
        StudyTask task = studyTaskDao.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问此任务");
        }

        return task;
    }

    /**
     * 获取某个学习计划的所有任务（历史记录）
     */
    public List<StudyTask> getTasksByPlanId(Integer userId, Integer planId) {
        // 先验证计划属于该用户
        StudyPlan plan = studyPlanDao.findById(planId)
                .orElseThrow(() -> new BusinessException(404, "计划不存在"));

        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问此计划");
        }

        return studyTaskDao.findByPlanIdOrderByReviewStageAsc(planId);
    }

    /**
     * 更新任务内容（AI生成的复习计划）
     */
    @Transactional
    public StudyTask updateTaskContent(Integer userId, Integer taskId, String content) {
        StudyTask task = getTaskById(userId, taskId);
        task.setDescription(content);
        return studyTaskDao.save(task);
    }

    // StudyTaskService.java

    /**
     * 直接创建第一次复习任务（计划完成时调用）
     * 不经过待生产阶段，直接创建 reviewStage = 1 的任务
     */
    @Transactional
    public void createFirstReviewTaskFromPlan(StudyPlan plan) {
        log.info("为计划 {} 直接创建第一次复习任务", plan.getId());

        StudyTask task = new StudyTask();
        task.setPlanId(plan.getId());
        task.setUserId(plan.getUserId());
        task.setTitle(plan.getTitle());
        task.setDescription(null);
        task.setTaskDate(LocalDate.now());
        task.setScheduledTime(null);
        task.setDurationMinutes(30);
        task.setStatus("pending");
        task.setReviewStage((short) 1);
        task.setCreatedAt(LocalDateTime.now());

        studyTaskDao.save(task);
    }
}