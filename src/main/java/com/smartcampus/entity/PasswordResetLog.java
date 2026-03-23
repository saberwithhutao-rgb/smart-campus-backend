package com.smartcampus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 密码重置日志表
 */
@Entity
@Table(name = "password_reset_logs")
@Data
public class PasswordResetLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "reset_time", nullable = false)
    private LocalDateTime resetTime;

    @Column(name = "reset_ip", length = 45)
    private String resetIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "success", nullable = false)
    private Boolean success = true;

    @Column(name = "fail_reason", length = 200)
    private String failReason;
}