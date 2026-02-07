package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "qa_history")
@Data
public class QaHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(columnDefinition = "TEXT")
    private String contexts; // JSON格式存储参考上下文

    private Boolean fromCache = false;
    private LocalDateTime askTime = LocalDateTime.now();
    private Integer responseTime; // 毫秒
    private String modelUsed = "qwen-max";

    // 用户反馈
    private Boolean helpful;
    private LocalDateTime feedbackTime;
}