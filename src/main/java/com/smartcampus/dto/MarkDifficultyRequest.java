package com.smartcampus.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.util.List;

@Data
public class MarkDifficultyRequest {
    @NotNull(message = "计划ID不能为空")
    private Long planId;

    @NotBlank(message = "难点内容不能为空")
    private String content;

    private List<String> tags;
}

