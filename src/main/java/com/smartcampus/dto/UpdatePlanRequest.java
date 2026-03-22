package com.smartcampus.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

@Data
public class UpdatePlanRequest {

    @Size(max = 200, message = "计划标题不能超过200字符")
    private String title;

    private String description;

    private String planType;

    private String subject;

    @Pattern(regexp = "easy|medium|hard", message = "难易程度必须是 easy/medium/hard")
    private String difficulty;

    @Pattern(regexp = "active|completed|paused", message = "状态必须是 active/completed/paused")
    private String status;

    private LocalDate startDate;

    private LocalDate endDate;
}