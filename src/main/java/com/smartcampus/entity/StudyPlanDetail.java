// src/main/java/com/smartcampus/entity/StudyPlanDetail.java
package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "study_plan_detail")
public class StudyPlanDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_plan_id", nullable = false)
    private Long studyPlanId;  // 对应表里的 study_plan_id

    @Column(name = "duration", length = 50)
    private String duration;    // 单次学习时长

    @Column(name = "level", length = 50)
    private String level;       // 难度级别

    @Column(name = "plan_details", columnDefinition = "text")
    private String planDetails; // AI生成的计划内容

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}