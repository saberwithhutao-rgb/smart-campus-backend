package com.smartcampus.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TaskStatusResponse {
    private String taskId;
    private String status; // "processing", "completed", "failed"
    private String question;
    private String answer; // 完成后才有
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage; // 失败时才有
    private String fileOriginalName; // 上传的文件名
}