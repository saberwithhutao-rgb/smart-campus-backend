package com.smartcampus.service;

import com.smartcampus.dto.*;
import com.smartcampus.entity.DifficultyMark;

import java.util.List;

public interface ReviewService {

    /**
     * 11.1 获取复习计划
     */
    ReviewPlan getReviewPlan(Integer userId, Long planId);

    /**
     * 11.2 生成复习曲线
     */
    ReviewCurve generateReviewCurve(Integer userId, GenerateReviewRequest request);

    /**
     * 11.3 标记难点
     */
    DifficultyMark markDifficulty(Integer userId, MarkDifficultyRequest request);

    /**
     * 获取用户的难点标记列表
     */
    List<DifficultyMark> getUserDifficultyMarks(Integer userId, Long planId);
}