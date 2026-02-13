package com.smartcampus.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class UpdateProgressRequest {

    @NotNull(message = "进度不能为空")
    @Min(value = 0, message = "进度不能小于0")
    @Max(value = 100, message = "进度不能大于100")
    private Short progressPercent;
}