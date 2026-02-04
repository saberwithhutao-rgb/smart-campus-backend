package com.smartcampus.repository;

import com.smartcampus.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    // 通过用户名查找
    Optional<User> findByUsername(String username);

    // 通过邮箱查找
    Optional<User> findByEmail(String email);

    // 检查用户名是否存在
    boolean existsByUsername(String username);

    // 检查邮箱是否存在
    boolean existsByEmail(String email);

    // 检查学号是否存在（自定义查询）
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.studentId = :studentId AND u.studentId IS NOT NULL")
    boolean existsByStudentId(@Param("studentId") String studentId);

    // 通过用户名或邮箱查找
    @Query("SELECT u FROM User u WHERE u.username = :identifier OR u.email = :identifier")
    Optional<User> findByUsernameOrEmail(@Param("identifier") String identifier);
}