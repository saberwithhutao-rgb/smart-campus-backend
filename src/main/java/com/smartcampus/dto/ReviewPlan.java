package com.smartcampus.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReviewPlan {
    private Long id;
    private Long userId;
    private String title;
    private List<ReviewItem> items;
    private LocalDateTime createdAt;
    private LocalDateTime nextReviewTime;
}

@Data
class ReviewItem {
    private String content;
    private String difficulty;
    private List<String> tags;
    private Integer reviewDay;
}