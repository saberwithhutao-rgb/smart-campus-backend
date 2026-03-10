package com.smartcampus.repository;

import com.smartcampus.entity.StudyTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudyTaskRepository extends JpaRepository<StudyTask, Integer> {

    /**
     * 根据用户ID查询所有任务，按任务日期倒序
     */
    List<StudyTask> findByUserIdOrderByTaskDateDesc(Integer userId);

    /**
     * 根据用户ID和状态查询任务
     */
    List<StudyTask> findByUserIdAndStatus(Integer userId, String status);

    /**
     * 根据用户ID和复习阶段查询任务
     */
    List<StudyTask> findByUserIdAndReviewStage(Integer userId, Short reviewStage);

    /**
     * 根据计划ID和用户ID查询任务，按复习阶段升序
     */
    List<StudyTask> findByPlanIdAndUserIdOrderByReviewStageAsc(Integer planId, Integer userId);

    /**
     * 获取待复习任务（pending状态，且任务日期小于等于今天）
     */
    @Query("SELECT t FROM StudyTask t WHERE t.userId = :userId AND t.status = 'pending' AND t.taskDate <= :today ORDER BY t.taskDate ASC")
    List<StudyTask> findPendingTasks(@Param("userId") Integer userId, @Param("today") LocalDate today);

    /**
     * 获取今天的复习任务
     */
    @Query("SELECT t FROM StudyTask t WHERE t.userId = :userId AND t.taskDate = :today ORDER BY t.scheduledTime ASC")
    List<StudyTask> findTodayTasks(@Param("userId") Integer userId, @Param("today") LocalDate today);

    /**
     * 获取逾期任务（pending状态，且任务日期小于今天）
     */
    @Query("SELECT t FROM StudyTask t WHERE t.userId = :userId AND t.status = 'pending' AND t.taskDate < :today ORDER BY t.taskDate ASC")
    List<StudyTask> findOverdueTasks(@Param("userId") Integer userId, @Param("today") LocalDate today);

    /**
     * 获取已完成的任务
     */
    List<StudyTask> findByUserIdAndStatusOrderByCompletedAtDesc(Integer userId, String status);

    /**
     * 统计用户各状态的任务数量
     */
    @Query("SELECT t.status, COUNT(t) FROM StudyTask t WHERE t.userId = :userId GROUP BY t.status")
    List<Object[]> countByStatus(@Param("userId") Integer userId);

    /**
     * 根据ID和用户ID查询任务（用于权限验证）
     */
    Optional<StudyTask> findByIdAndUserId(Integer id, Integer userId);
}