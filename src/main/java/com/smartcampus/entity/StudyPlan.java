package com.smartcampus.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "study_plans", schema = "public")
public class StudyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull(message = "用户ID不能为空")
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @NotBlank(message = "计划标题不能为空")
    @Size(max = 200, message = "计划标题不能超过200字符")
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "plan_type", length = 30)
    private String planType;

    @NotBlank(message = "学科不能为空")
    @Size(max = 100, message = "学科名称不能超过100字符")
    @Column(name = "subject", length = 100)
    private String subject;

    @Column(name = "difficulty", length = 20)
    private String difficulty = "medium";

    @Column(name = "status", length = 20)
    private String status = "active";

    @NotNull(message = "开始日期不能为空")
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}