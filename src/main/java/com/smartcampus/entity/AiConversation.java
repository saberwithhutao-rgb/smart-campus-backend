package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_conversations")
@Data
public class AiConversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String title;

    @Column(name = "session_id")
    private String sessionId;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    private Long fileId;

    @Column(name = "question_type")
    private String questionType = "text";

    @Column(name = "token_usage")
    private Integer tokenUsage = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    private Short rating = 0;
}