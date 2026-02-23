// src/main/java/com/smartcampus/repository/StudyPlanDetailRepository.java
package com.smartcampus.repository;

import com.smartcampus.entity.StudyPlanDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StudyPlanDetailRepository extends JpaRepository<StudyPlanDetail, Long> {
    // 按学习计划ID查找
    List<StudyPlanDetail> findByStudyPlanId(Long studyPlanId);
}