package com.smartcampus.dao;

import com.smartcampus.entity.StudyTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StudyTaskDao extends JpaRepository<StudyTask, Integer> {

    // 获取用户的待复习任务（按日期排序）
    List<StudyTask> findByUserIdAndStatusOrderByTaskDateAsc(Integer userId, String status);

    // 获取特定日期的待复习任务
    List<StudyTask> findByUserIdAndTaskDateAndStatus(Integer userId, LocalDate taskDate, String status);

    // 获取逾期任务
    List<StudyTask> findByUserIdAndTaskDateLessThanAndStatus(Integer userId, LocalDate taskDate, String status);

    // 获取计划的所有任务
    List<StudyTask> findByPlanId(Integer planId);

    // 删除计划的所有任务
    @Modifying
    @Transactional
    void deleteByPlanId(Integer planId);

    // 更新任务状态
    @Modifying
    @Transactional
    @Query("UPDATE StudyTask t SET t.status = :status, t.completedAt = CURRENT_TIMESTAMP WHERE t.id = :id")
    int updateStatus(@Param("id") Integer id, @Param("status") String status);
}