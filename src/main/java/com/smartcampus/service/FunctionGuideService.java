package com.smartcampus.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FunctionGuideService {

    private final Map<String, FunctionGuide> keywordIndex = new ConcurrentHashMap<>();
    private final List<FunctionGuide> allGuides = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            loadFromJsonFile();
            log.info("功能文档加载成功，共 {} 个功能，{} 个关键词索引",
                    allGuides.size(), keywordIndex.size());
        } catch (Exception e) {
            log.error("加载功能文档失败", e);
            // 加载失败时使用空列表，不影响服务启动
        }
    }

    /**
     * 从 JSON 文件加载功能文档
     */
    private void loadFromJsonFile() throws Exception {
        ClassPathResource resource = new ClassPathResource("function-guides.json");
        try (InputStream inputStream = resource.getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(inputStream,
                    new TypeReference<Map<String, Object>>() {});

            List<Map<String, Object>> guidesJson = (List<Map<String, Object>>) root.get("guides");

            for (Map<String, Object> guideJson : guidesJson) {
                List<String> keywords = (List<String>) guideJson.get("keywords");
                String title = (String) guideJson.get("title");
                String content = (String) guideJson.get("content");

                FunctionGuide guide = new FunctionGuide(keywords, title, content);
                allGuides.add(guide);

                // 建立关键词索引（每个关键词都指向这个功能）
                for (String keyword : keywords) {
                    keywordIndex.put(keyword.toLowerCase(), guide);
                }
            }
        }
    }

    /**
     * 热加载（无需重启服务即可重新加载文档）
     */
    public void reload() {
        keywordIndex.clear();
        allGuides.clear();
        init();
        log.info("功能文档已热加载");
    }

    /**
     * 搜索功能说明
     */
    public SearchResult search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchResult(false, null, null, "请输入要搜索的功能名称");
        }

        String lowerQuery = query.toLowerCase();

        // 精确匹配关键词
        if (keywordIndex.containsKey(lowerQuery)) {
            FunctionGuide guide = keywordIndex.get(lowerQuery);
            return new SearchResult(true, guide.getTitle(), guide.getContent(), null);
        }

        // 模糊匹配：查询包含某个关键词，或某个关键词包含查询
        for (Map.Entry<String, FunctionGuide> entry : keywordIndex.entrySet()) {
            if (lowerQuery.contains(entry.getKey()) || entry.getKey().contains(lowerQuery)) {
                FunctionGuide guide = entry.getValue();
                return new SearchResult(true, guide.getTitle(), guide.getContent(), null);
            }
        }

        // 没找到
        String suggestion = allGuides.stream()
                .flatMap(g -> g.getKeywords().stream())
                .limit(8)
                .collect(Collectors.joining("、"));

        return new SearchResult(false, null, null,
                "未找到「" + query + "」相关的功能说明。\n\n可尝试搜索：" + suggestion);
    }

    /**
     * 获取所有功能列表
     */
    public List<FunctionGuide> getAllFunctions() {
        return new ArrayList<>(allGuides);
    }

    // ========== 内部类 ==========

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class FunctionGuide {
        private List<String> keywords;
        private String title;
        private String content;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SearchResult {
        private boolean found;
        private String title;
        private String content;
        private String errorMessage;
    }
}