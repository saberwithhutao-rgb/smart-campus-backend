package com.smartcampus.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

@Data
public class UpdatePlanRequest {

    @Size(max = 200, message = "计划标题不能超过200字符")
    private String title;

    private String description;

    @Pattern(regexp = "review|learning|project", message = "计划类型必须是 review/learning/project")
    private String planType;

    @Size(max = 100, message = "学科名称不能超过100字符")
    private String subject;

    @Pattern(regexp = "easy|medium|hard", message = "难易程度必须是 easy/medium/hard")
    private String difficulty;

    @Pattern(regexp = "active|completed|paused", message = "状态必须是 active/completed/paused")
    private String status;

    @Min(value = 0, message = "进度不能小于0")
    @Max(value = 100, message = "进度不能大于100")
    private Short progressPercent;

    @PastOrPresent(message = "开始日期不能是未来日期")
    private LocalDate startDate;

    @Future(message = "结束日期必须是未来日期")
    private LocalDate endDate;
}