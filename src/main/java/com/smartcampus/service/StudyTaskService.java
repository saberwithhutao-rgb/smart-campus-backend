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
     * 创建待生产复习任务（计划完成时调用）
     */
    @Transactional
    public StudyTask createInitialReviewTask(StudyPlan plan) {
        log.info("为计划 {} 创建待生产复习任务", plan.getId());

        StudyTask task = new StudyTask();
        task.setPlanId(plan.getId());
        task.setUserId(plan.getUserId());
        task.setTitle(plan.getTitle());
        task.setDescription(plan.getDescription());
        task.setTaskDate(LocalDate.now());  // 完成当天
        task.setScheduledTime(null);
        task.setDurationMinutes(30);
        task.setStatus("pending");
        task.setDifficulty("pending");  // ✅ 待生产标识
        task.setReviewStage((short) 0);  // 0表示待生产
        task.setCreatedAt(LocalDateTime.now());

        return studyTaskDao.save(task);
    }

    /**
     * 创建第一次复习任务（当用户生成复习计划时）
     */
    @Transactional
    public StudyTask createFirstReviewTask(StudyTask initialTask) {
        StudyTask firstTask = new StudyTask();
        firstTask.setPlanId(initialTask.getPlanId());
        firstTask.setUserId(initialTask.getUserId());
        firstTask.setTitle(initialTask.getTitle());
        firstTask.setDescription(initialTask.getDescription());
        firstTask.setTaskDate(LocalDate.now().plusDays(1));  // 明天
        firstTask.setScheduledTime(null);
        firstTask.setDurationMinutes(30);
        firstTask.setStatus("pending");
        firstTask.setDifficulty("medium");  // ✅ 默认中等
        firstTask.setReviewStage((short) 1);
        firstTask.setCreatedAt(LocalDateTime.now());

        return studyTaskDao.save(firstTask);
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
        nextTask.setDifficulty(currentTask.getDifficulty());  // 沿用难度
        nextTask.setReviewStage((short) nextStage);
        nextTask.setCreatedAt(LocalDateTime.now());

        return studyTaskDao.save(nextTask);
    }

    /**
     * 完成任务（待生产任务 → 生成第一次复习）
     */
    @Transactional
    public void completeInitialTask(Integer userId, Integer taskId) {
        StudyTask task = studyTaskDao.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        // 验证权限
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作此任务");
        }

        // 检查是否是待生产任务
        if (!"pending".equals(task.getDifficulty()) || task.getReviewStage() != 0) {
            throw new BusinessException(400, "只能对待生产任务执行此操作");
        }

        // ✅ 创建第一次复习任务
        createFirstReviewTask(task);

        // ✅ 删除待生产任务
        studyTaskDao.delete(task);

        log.info("待生产任务 {} 已完成，生成了第一次复习任务", taskId);
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
     * 获取用户的待复习任务（包括待生产和普通任务）
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