package com.smartcampus.dto;

import lombok.Data;
import java.util.List;

@Data
public class QaResponse {
    private String answer;
    private Boolean fromCache = false;
    private List<String> contexts;
    private List<String> sourceDocuments; // 来源文档信息
    private String modelUsed;
    private Integer responseTime; // 毫秒
    private String queryId; // 用于后续反馈
}