package com.smartcampus.dto;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

@Data
public class StudyTaskDTO {
    private Integer id;
    private Integer planId;
    private Integer userId;
    private String title;
    private String description;
    private String taskDate;
    private String scheduledTime;
    private Integer durationMinutes;
    private String status;
    private Integer reviewStage;
    private String completedAt;
    private String createdAt;
}