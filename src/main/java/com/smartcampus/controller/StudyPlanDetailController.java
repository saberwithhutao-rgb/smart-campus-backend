// src/main/java/com/smartcampus/controller/StudyPlanDetailController.java
package com.smartcampus.controller;

import com.smartcampus.entity.StudyPlanDetail;
import com.smartcampus.repository.StudyPlanDetailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/study-plan-details")
@RequiredArgsConstructor
public class StudyPlanDetailController {

    private final StudyPlanDetailRepository studyPlanDetailRepository;

    /**
     * 获取学习计划的所有历史详情
     * GET /api/study-plan-details/plan/{studyPlanId}
     */
    @GetMapping("/plan/{studyPlanId}")
    public ResponseEntity<?> getDetailsByPlanId(@PathVariable Long studyPlanId) {
        List<StudyPlanDetail> details = studyPlanDetailRepository.findByStudyPlanId(studyPlanId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", details
        ));
    }

    /**
     * 获取单个详情
     * GET /api/study-plan-details/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getDetailById(@PathVariable Long id) {
        Optional<StudyPlanDetail> detail = studyPlanDetailRepository.findById(id);
        if (detail.isPresent()) {
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", detail.get()
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "code", 404,
                    "message", "详情不存在"
            ));
        }
    }

    /**
     * 删除详情
     * DELETE /api/study-plan-details/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDetail(@PathVariable Long id) {
        if (studyPlanDetailRepository.existsById(id)) {
            studyPlanDetailRepository.deleteById(id);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "删除成功"
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "code", 404,
                    "message", "详情不存在"
            ));
        }
    }
}