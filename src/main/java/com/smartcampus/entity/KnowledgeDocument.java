package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_documents")
@Data
public class KnowledgeDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String sourceType; // "PDF", "TXT", "URL", "MANUAL"
    private String sourcePath;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String vectorEmbedding; // 存储向量（Base64或JSON）

    private String embeddingModel = "text-embedding-v2";
    private Integer tokenCount;
    private String category; // "课程资料", "校规", "常见问题"
    private LocalDateTime uploadTime = LocalDateTime.now();
    private String uploader;
    private Boolean isActive = true;
}