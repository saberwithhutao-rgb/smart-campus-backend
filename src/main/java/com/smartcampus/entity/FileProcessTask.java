package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "file_process_tasks")
@Data
public class FileProcessTask {
    @Id
    private String taskId; // UUID

    private String userId;
    private String originalFilename;
    private String fileType; // "pdf", "docx", "txt", "image", "voice"
    private String filePath; // 服务器存储路径
    private String question;

    @Column(columnDefinition = "TEXT")
    private String extractedText; // 从文件中提取的文本

    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PROCESSING;

    @Column(columnDefinition = "TEXT")
    private String aiAnswer; // AI生成的答案

    private String errorMessage;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private Integer processDuration; // 处理耗时(ms)

    public enum TaskStatus {
        PROCESSING, COMPLETED, FAILED
    }
}