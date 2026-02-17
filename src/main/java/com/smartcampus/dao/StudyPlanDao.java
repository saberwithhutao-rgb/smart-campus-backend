package com.smartcampus.dao;

import com.smartcampus.entity.StudyPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudyPlanDao extends JpaRepository<StudyPlan, Integer> {

    // 根据用户ID查询所有计划
    Page<StudyPlan> findByUserId(Integer userId, Pageable pageable);

    // 根据用户ID和状态查询
    Page<StudyPlan> findByUserIdAndStatus(Integer userId, String status, Pageable pageable);

    // 根据用户ID和计划类型查询
    Page<StudyPlan> findByUserIdAndPlanType(Integer userId, String planType, Pageable pageable);

    // 根据用户ID、学科模糊查询
    @Query("SELECT s FROM StudyPlan s WHERE s.userId = :userId AND s.subject LIKE %:subject%")
    Page<StudyPlan> findByUserIdAndSubjectLike(@Param("userId") Integer userId,
                                               @Param("subject") String subject,
                                               Pageable pageable);

    // 多条件动态查询（JPA规范方法）
    Page<StudyPlan> findByUserIdAndStatusAndPlanTypeAndSubjectContaining(
            Integer userId, String status, String planType, String subject, Pageable pageable);

    // 查询单个计划（同时验证用户权限）
    Optional<StudyPlan> findByIdAndUserId(Integer id, Integer userId);

    // 删除计划（同时验证用户权限）
    int deleteByIdAndUserId(Integer id, Integer userId);

    // 获取用户的学习日程
    @Query("SELECT s FROM StudyPlan s WHERE s.userId = :userId " +
            "AND (:planId IS NULL OR s.id = :planId) " +
            "AND (:startDate IS NULL OR s.startDate >= :startDate) " +
            "AND (:endDate IS NULL OR s.startDate <= :endDate) " +
            "ORDER BY s.startDate ASC")
    List<StudyPlan> findSchedule(@Param("userId") Integer userId,
                                 @Param("planId") Integer planId,
                                 @Param("startDate") LocalDate startDate,
                                 @Param("endDate") LocalDate endDate);

    // 统计总数
    long countByUserId(Integer userId);

    // 统计完成数量
    long countByUserIdAndStatus(Integer userId, String status);
}