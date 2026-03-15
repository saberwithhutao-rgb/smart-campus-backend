package com.smartcampus.vo;

import com.smartcampus.entity.ReviewSuggestion;
import com.smartcampus.entity.StudyTask;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class StudyTaskVO extends StudyTask {
    private ReviewSuggestion currentSuggestion;
}