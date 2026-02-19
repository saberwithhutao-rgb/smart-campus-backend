package com.smartcampus.repository;

import com.smartcampus.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    // 按用户ID和会话ID查询，按时间倒序（带分页）
    @Query("SELECT a FROM AiConversation a WHERE a.userId = :userId AND a.sessionId = :sessionId ORDER BY a.createdAt DESC")
    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            org.springframework.data.domain.Pageable pageable);

    // 获取用户最新对话（按时间倒序，带分页）
    @Query("SELECT a FROM AiConversation a WHERE a.userId = :userId ORDER BY a.createdAt DESC")
    List<AiConversation> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable);

    // 简化版本：使用命名查询（无分页）
    List<AiConversation> findByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, String sessionId);

    List<AiConversation> findByUserIdOrderByCreatedAtDesc(Long userId);

    // ========== 新增：按会话ID查询，按时间正序（用于显示完整对话历史） ==========
    /**
     * 按会话ID查询所有对话记录，按创建时间正序排列
     * 用于获取一个会话的完整聊天记录（从旧到新）
     */
    List<AiConversation> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    // 统计用户对话数量
    Long countByUserId(Long userId);

    // 按会话ID分组查询（仅返回不同的sessionId）
    @Query("SELECT DISTINCT a.sessionId FROM AiConversation a WHERE a.userId = :userId")
    List<String> findDistinctSessionIdByUserId(@Param("userId") Long userId);

    // ================== 新增方法（用于历史对话功能） ==================

    /**
     * 统计指定会话中的消息数量
     */
    long countByUserIdAndSessionId(Long userId, String sessionId);

    /**
     * 检查会话是否存在且属于指定用户
     */
    boolean existsBySessionIdAndUserId(String sessionId, Long userId);

    /**
     * 删除整个会话的所有记录
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM AiConversation a WHERE a.sessionId = :sessionId AND a.userId = :userId")
    int deleteBySessionIdAndUserId(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /**
     * 统计用户所有对话的 token 使用总量
     */
    @Query("SELECT SUM(a.tokenUsage) FROM AiConversation a WHERE a.userId = :userId")
    Integer sumTokenUsageByUserId(@Param("userId") Long userId);

    /**
     * 统计用户的不同会话数量
     */
    @Query("SELECT COUNT(DISTINCT a.sessionId) FROM AiConversation a WHERE a.userId = :userId")
    long countDistinctSessionsByUserId(@Param("userId") Long userId);

    /**
     * 获取用户的评分统计
     */
    @Query("SELECT " +
            "SUM(CASE WHEN a.rating = 1 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.rating = -1 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.rating = 0 THEN 1 ELSE 0 END) " +
            "FROM AiConversation a WHERE a.userId = :userId")
    Object[] getRatingStatsByUserId(@Param("userId") Long userId);

    /**
     * 获取用户的会话列表摘要（每个会话一条记录）
     */
    @Query(value = "SELECT " +
            "    a.session_id, " +
            "    (SELECT a2.title FROM ai_conversations a2 WHERE a2.session_id = a.session_id AND a2.title IS NOT NULL ORDER BY a2.created_at ASC LIMIT 1) as title, " +
            "    (SELECT a3.question FROM ai_conversations a3 WHERE a3.session_id = a.session_id ORDER BY a3.created_at DESC LIMIT 1) as preview, " +
            "    MIN(a.created_at) as create_time, " +
            "    COUNT(a.id) as message_count, " +
            "    MAX(a.file_id) as file_id " +
            "FROM ai_conversations a " +
            "WHERE a.user_id = ?1 " +
            "GROUP BY a.session_id " +
            "ORDER BY create_time DESC", nativeQuery = true)
    List<Object[]> findSessionSummaries(Long userId);
}