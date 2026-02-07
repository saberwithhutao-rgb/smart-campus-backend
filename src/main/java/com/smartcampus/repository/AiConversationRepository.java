package com.smartcampus.repository;

import com.smartcampus.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    // 按用户ID和会话ID查询，按时间倒序
    @Query("SELECT a FROM AiConversation a WHERE a.userId = :userId AND a.sessionId = :sessionId ORDER BY a.createdAt DESC")
    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            org.springframework.data.domain.Pageable pageable);

    // 获取用户最新对话（按时间倒序）
    @Query("SELECT a FROM AiConversation a WHERE a.userId = :userId ORDER BY a.createdAt DESC")
    List<AiConversation> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable);

    // 简化版本：使用命名查询
    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, String sessionId);

    List<AiConversation> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 统计用户对话数量
    Long countByUserId(Long userId);

    // 按会话ID分组查询
    @Query("SELECT DISTINCT a.sessionId FROM AiConversation a WHERE a.userId = :userId")
    List<String> findDistinctSessionIdByUserId(@Param("userId") Long userId);
}