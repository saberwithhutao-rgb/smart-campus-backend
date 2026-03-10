package com.smartcampus.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class GenerateReviewAdviceRequest {

    @NotNull(message = "任务ID不能为空")
    private Long taskId;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotNull(message = "复习阶段不能为空")
    private Integer reviewStage;
}