package com.smartcampus.repository;

import com.smartcampus.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {
    List<ReviewRecord> findByUserIdAndPlanId(Integer userId, Long planId);
    List<ReviewRecord> findByUserIdAndCompletedFalse(Integer userId);
    List<ReviewRecord> findByUserIdAndReviewDay(Integer userId, Integer reviewDay);
}