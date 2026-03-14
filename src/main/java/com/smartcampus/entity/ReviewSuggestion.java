package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "review_suggestions")
@Data
public class ReviewSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Integer taskId;

    @Column(name = "plan_id", nullable = false)
    private Integer planId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "review_stage", nullable = false)  // 新增字段
    private Integer reviewStage;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "is_current")
    private Boolean isCurrent = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}