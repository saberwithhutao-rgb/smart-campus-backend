package com.smartcampus.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.vladmihalcea.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "users",
        indexes = {
                @Index(name = "idx_users_username", columnList = "username"),
                @Index(name = "idx_users_email", columnList = "email"),
                @Index(name = "idx_users_student_id", columnList = "student_id"),
                @Index(name = "idx_users_status", columnList = "status")
        })
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "username", unique = true, nullable = false, length = 50)
    private String username;

    @Column(name = "password", nullable = false, length = 100)
    private String password;

    @Column(name = "email", unique = true, nullable = false, length = 100)
    private String email;

    @Column(name = "gender")
    private Integer gender = 0; // 0-未知，1-男，2-女

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "status", nullable = false)
    private Integer status = 1; // 0-禁用，1-正常

    @Column(name = "role", nullable = false, length = 20)
    private String role = "user"; // 默认普通用户

    @Column(name = "student_id", length = 20)
    private String studentId;

    @Column(name = "major", length = 100)
    private String major;

    @Column(name = "college", length = 100)
    private String college;

    @Column(name = "grade", length = 20)
    private String grade; // 大一、大二、大三、大四

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginAt;

    @Type(JsonType.class)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (metadata == null) {
            metadata = new HashMap<>();
        }
    }

    // 辅助方法，用于前端显示
    public String getGenderText() {
        switch (gender) {
            case 1: return "男";
            case 2: return "女";
            default: return "未知";
        }
    }

    public String getStatusText() {
        return status == 1 ? "正常" : "禁用";
    }

    public String getAvatar() {
        // 如果没有头像，返回默认头像
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            return "/api/avatars/default-avatar.png";
        }
        return avatarUrl;
    }
}