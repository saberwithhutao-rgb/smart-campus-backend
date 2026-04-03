package com.smartcampus.dao;

import com.smartcampus.entity.ReviewSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewSuggestionDao extends JpaRepository<ReviewSuggestion, Long> {

    List<ReviewSuggestion> findByTaskIdOrderByVersionDesc(Integer taskId);

    Optional<ReviewSuggestion> findByTaskIdAndIsCurrentTrue(Integer taskId);

    List<ReviewSuggestion> findByPlanIdOrderByCreatedAtDesc(Integer planId);

    @Modifying
    @Transactional
    @Query("UPDATE ReviewSuggestion s SET s.isCurrent = false WHERE s.taskId = :taskId")
    void resetCurrentFlag(@Param("taskId") Integer taskId);

    @Query("SELECT COALESCE(MAX(s.version), 0) FROM ReviewSuggestion s WHERE s.taskId = :taskId")
    Integer getMaxVersion(@Param("taskId") Integer taskId);

    @Modifying
    @Transactional
    void deleteByPlanId(Integer planId);

    @Modifying
    @Transactional
    void deleteByTaskId(Integer taskId);
}