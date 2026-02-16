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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyTaskService {

    private final StudyTaskDao studyTaskDao;

    // 复习间隔（天数）
    private static final int[] REVIEW_INTERVALS = {1, 3, 7, 15, 30};

    /**
     * 创建第一次复习任务（当计划完成时）
     */
    @Transactional
    public StudyTask createFirstReviewTask(StudyPlan plan) {

        LocalDate taskDate;
        if (plan.getUpdatedAt() != null) {
            taskDate = plan.getUpdatedAt().toLocalDate().plusDays(1);
        }else{
            taskDate = LocalDate.now().plusDays(1);
        }

        StudyTask task = new StudyTask();
        task.setPlanId(plan.getId());
        task.setUserId(plan.getUserId());
        task.setTitle(plan.getTitle());
        task.setDescription(plan.getDescription());
        task.setTaskDate(taskDate);
        task.setScheduledTime(null);
        task.setDurationMinutes(30);
        task.setStatus("pending");
        task.setReviewStage((short) 1); // 第一次复习
        task.setCreatedAt(LocalDateTime.now());

        return studyTaskDao.save(task);
    }

    /**
     * 创建下一次复习任务（当完成当前任务时）
     */
    @Transactional
    public StudyTask createNextReviewTask(StudyTask currentTask) {
        int currentStage = currentTask.getReviewStage();

        // 如果已经是第5次复习，不再创建新任务
        if (currentStage >= 5) {
            log.info("计划 {} 已完成所有5次复习", currentTask.getPlanId());
            return null;
        }

        int nextStage = currentStage + 1;

        LocalDate nextDate;
        if (currentTask.getCompletedAt() != null) {
            nextDate = currentTask.getCompletedAt()
                    .toLocalDate()
                    .plusDays(REVIEW_INTERVALS[nextStage - 1]);
        } else {
            // 如果没有完成时间（理论上不会发生），用当前时间
            nextDate = LocalDate.now().plusDays(REVIEW_INTERVALS[nextStage - 1]);
        }

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

        return studyTaskDao.save(nextTask);
    }

    /**
     * 完成任务
     */
    @Transactional
    public StudyTask completeTask(Integer userId, Integer taskId) {
        StudyTask task = studyTaskDao.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        // 验证权限
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作此任务");
        }

        // 标记为完成
        task.setStatus("completed");
        task.setCompletedAt(LocalDateTime.now());
        studyTaskDao.save(task);

        // 创建下一次复习任务
        createNextReviewTask(task);

        log.info("任务 {} 已完成，并创建了下一次复习", taskId);
        return task;
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
}