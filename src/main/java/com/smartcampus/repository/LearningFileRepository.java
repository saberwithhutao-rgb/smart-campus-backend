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
    int updateSummary(@Param("fileId") Long fileId, @Param("summary") String summary);

    // 按状态查询
    List<LearningFile> findByUserIdAndStatusOrderByUploadTimeDesc(Long userId, String status);

    // 按文件类型查询
    List<LearningFile> findByUserIdAndFileTypeOrderByUploadTimeDesc(Long userId, String fileType);
}