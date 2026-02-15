package com.smartcampus.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.util.List;

@Data
public class GenerateReviewRequest {
    @NotEmpty(message = "计划ID不能为空")
    private List<Long> planIds;

    private List<String> difficultyTags;

    @Pattern(regexp = "light|medium|intense", message = "复习强度必须是 light/medium/intense")
    private String reviewIntensity = "medium";
}