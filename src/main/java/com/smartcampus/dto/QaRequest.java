package com.smartcampus.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class QaRequest {
    @NotBlank(message = "问题不能为空")
    private String question;

    private MultipartFile file; // 支持文件上传
    private Boolean isVoice = false; // 是否为语音输入

    // 不再需要sessionId和model，按文档规范来
}