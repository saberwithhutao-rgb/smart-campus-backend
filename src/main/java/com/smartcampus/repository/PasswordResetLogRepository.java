package com.smartcampus.repository;

import com.smartcampus.entity.PasswordResetLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PasswordResetLogRepository extends JpaRepository<PasswordResetLog, Long> {
    List<PasswordResetLog> findByUserIdOrderByResetTimeDesc(Integer userId);
    List<PasswordResetLog> findByEmailOrderByResetTimeDesc(String email);
}