package com.smartcampus.repository;

import com.smartcampus.entity.LearningFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface LearningFileRepository extends JpaRepository<LearningFile, Long> {

    // 按用户ID查询文件
    List<LearningFile> findByUserIdOrderByUploadTimeDesc(Long userId);

    // 更新文件摘要
    @Modifying
    @Transactional
    @Query("UPDATE LearningFile l SET l.summary = :summary WHERE l.id = :fileId")
    void updateSummary(@Param("fileId") Long fileId, @Param("summary") String summary);

    // 按状态查询
    List<LearningFile> findByUserIdAndStatusOrderByUploadTimeDesc(Long userId, String status);

    // 按文件类型查询
    List<LearningFile> findByUserIdAndFileTypeOrderByUploadTimeDesc(Long userId, String fileType);

    /**
     * 查找用户的所有文件，包含摘要
     */
    @Query("SELECT l FROM LearningFile l WHERE l.userId = :userId AND l.status = 'active' ORDER BY l.uploadTime DESC")
    List<LearningFile> findActiveByUserId(@Param("userId") Long userId);

    /**
     * 统计用户的文件数量
     */
    long countByUserIdAndStatus(Long userId, String status);
}