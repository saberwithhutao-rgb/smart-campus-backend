package com.smartcampus.controller;

import com.smartcampus.service.FunctionGuideService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/function-guide")
@RequiredArgsConstructor
public class FunctionGuideController {

    private final FunctionGuideService functionGuideService;

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String query) {
        var result = functionGuideService.search(query);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("success", result.isFound());

        if (result.isFound()) {
            Map<String, Object> data = new HashMap<>();
            data.put("title", result.getTitle());
            data.put("content", result.getContent());
            response.put("data", data);
        } else {
            response.put("message", result.getErrorMessage());
        }

        return response;
    }

    @GetMapping("/list")
    public Map<String, Object> listAll() {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", functionGuideService.getAllFunctions());
        return response;
    }

    /**
     * 热加载接口（可选，管理员用）
     */
    @PostMapping("/admin/reload")
    public Map<String, Object> reload() {
        functionGuideService.reload();
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "功能文档已重新加载");
        return response;
    }
}