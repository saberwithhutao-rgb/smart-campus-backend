package com.smartcampus.repository;

import com.smartcampus.entity.DifficultyMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DifficultyMarkRepository extends JpaRepository<DifficultyMark, Long> {
    List<DifficultyMark> findByUserIdAndPlanId(Integer userId, Long planId);
    List<DifficultyMark> findByUserId(Integer userId);
}