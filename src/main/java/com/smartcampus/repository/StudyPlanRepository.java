package com.smartcampus.repository;

import com.smartcampus.entity.StudyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StudyPlanRepository extends JpaRepository<StudyPlan, Integer> {

    /**
     * 根据用户ID查询所有学习计划
     */
    List<StudyPlan> findByUserId(Integer userId);

    /**
     * 根据用户ID和状态查询学习计划
     */
    List<StudyPlan> findByUserIdAndStatus(Integer userId, String status);

    /**
     * 根据用户ID和计划类型查询学习计划
     */
    List<StudyPlan> findByUserIdAndPlanType(Integer userId, String planType);

    /**
     * 根据用户ID和开始日期范围查询学习计划（用于统计）
     */
    List<StudyPlan> findByUserIdAndStartDateBetween(Integer userId, LocalDate startDate, LocalDate endDate);

    /**
     * 统计用户的计划数量（按时间范围）
     */
    @Query("SELECT COUNT(s) FROM StudyPlan s WHERE s.userId = :userId AND s.startDate BETWEEN :startDate AND :endDate")
    long countByUserIdAndStartDateBetween(@Param("userId") Integer userId,
                                          @Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate);

    /**
     * 统计用户已完成计划数量（按时间范围）
     */
    @Query("SELECT COUNT(s) FROM StudyPlan s WHERE s.userId = :userId AND s.status = 'completed' AND s.startDate BETWEEN :startDate AND :endDate")
    long countCompletedByUserIdAndStartDateBetween(@Param("userId") Integer userId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);
}