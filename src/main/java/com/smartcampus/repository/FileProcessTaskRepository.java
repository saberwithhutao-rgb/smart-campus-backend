package com.smartcampus.repository;

import com.smartcampus.entity.FileProcessTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileProcessTaskRepository extends JpaRepository<FileProcessTask, String> {
}