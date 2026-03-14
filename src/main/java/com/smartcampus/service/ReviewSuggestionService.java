package com.smartcampus.service;

import com.smartcampus.dao.ReviewSuggestionDao;
import com.smartcampus.dao.StudyTaskDao;
import com.smartcampus.entity.ReviewSuggestion;
import com.smartcampus.entity.StudyTask;
import com.smartcampus.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewSuggestionService {

    private final ReviewSuggestionDao suggestionDao;
    private final StudyTaskDao studyTaskDao;

    @Transactional
    public ReviewSuggestion createSuggestion(Integer userId, Integer taskId, String content) {
        StudyTask task = studyTaskDao.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作");
        }

        suggestionDao.resetCurrentFlag(taskId);

        Integer newVersion = suggestionDao.getMaxVersion(taskId) + 1;

        ReviewSuggestion suggestion = new ReviewSuggestion();
        suggestion.setTaskId(taskId);
        suggestion.setPlanId(task.getPlanId());
        suggestion.setUserId(userId);
        suggestion.setReviewStage(Integer.valueOf(task.getReviewStage()));  // 设置 stage
        suggestion.setContent(content);
        suggestion.setVersion(newVersion);
        suggestion.setIsCurrent(true);
        suggestion.setCreatedAt(LocalDateTime.now());

        return suggestionDao.save(suggestion);
    }

    public ReviewSuggestion getCurrentSuggestion(Integer taskId) {
        return suggestionDao.findByTaskIdAndIsCurrentTrue(taskId).orElse(null);
    }

    public List<ReviewSuggestion> getTaskSuggestions(Integer taskId) {
        return suggestionDao.findByTaskIdOrderByVersionDesc(taskId);
    }

    public List<ReviewSuggestion> getPlanSuggestions(Integer planId) {
        return suggestionDao.findByPlanIdOrderByCreatedAtDesc(planId);
    }
}