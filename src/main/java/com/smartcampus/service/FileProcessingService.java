package com.smartcampus.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

@Service
@Slf4j
public class FileProcessingService {

    private final Path fileStorageLocation;

    public FileProcessingService() {
        this.fileStorageLocation = Paths.get("/opt/smart-campus/uploads")
                .toAbsolutePath().normalize();

        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new RuntimeException("无法创建文件上传目录", ex);
        }
    }

    public String extractTextFromFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String extension = getFileExtension(filename);

        try {
            return switch (extension.toLowerCase()) {
                case "pdf" -> extractTextFromPdf(file);
                case "docx" -> extractTextFromDocx(file);
                case "txt" -> extractTextFromTxt(file);
                case "jpg", "jpeg", "png" ->
                    // TODO: 集成OCR识别
                        "图片文件，待集成OCR功能";
                case "mp3", "wav" ->
                    // TODO: 集成语音识别
                        "语音文件，待集成ASR功能";
                default -> throw new UnsupportedOperationException("不支持的文件格式: " + extension);
            };
        } catch (Exception e) {
            log.error("文件解析失败: {}", filename, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage());
        }
    }

    public String storeFile(MultipartFile file, String taskId) {
        String filename = taskId + "_" + file.getOriginalFilename();

        try {
            // 检查文件名是否包含非法字符
            if (filename.contains("..")) {
                throw new RuntimeException("文件名包含非法路径序列: " + filename);
            }

            Path targetLocation = this.fileStorageLocation.resolve(filename);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            return targetLocation.toString();
        } catch (IOException ex) {
            throw new RuntimeException("无法存储文件: " + filename, ex);
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private String extractTextFromDocx(MultipartFile file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
            return extractor.getText();
        }
    }

    private String extractTextFromTxt(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    public boolean isSupportedFileType(String filename) {
        String[] supported = {"pdf", "docx", "txt", "jpg", "jpeg", "png", "mp3", "wav", "ppt", "pptx"};
        String extension = getFileExtension(filename).toLowerCase();

        for (String type : supported) {
            if (type.equals(extension)) {
                return true;
            }
        }
        return false;
    }
    /**
     * 从已保存的文件路径提取文本内容
     * @param filePath 文件路径
     * @return 提取的文本内容
     */
    public String extractTextFromFileByPath(String filePath) {
        File file = new File(filePath);
        log.info("开始解析文件");
        if (!file.exists()) {
            throw new RuntimeException("文件不存在: " + filePath);
        }

        String filename = file.getName();
        String extension = getFileExtension(filename);

        try {
            return switch (extension.toLowerCase()) {
                case "pdf" -> extractTextFromPdfFile(file);
                case "docx" -> extractTextFromDocxFile(file);
                case "txt" -> extractTextFromTxtFile(file);
                default -> throw new UnsupportedOperationException("不支持的文件格式: " + extension);
            };
        } catch (Exception e) {
            log.error("文件解析失败: {}", filename, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage());
        }
    }

    private String extractTextFromPdfFile(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private String extractTextFromDocxFile(File file) throws IOException {
        log.info("开始解析 DOCX 文件，大小: {} MB", file.length() / (1024*1024));
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try (FileInputStream fis = new FileInputStream(file);
                 XWPFDocument doc = new XWPFDocument(fis)) {

                log.info("XWPFDocument 加载完成，耗时: {} ms", System.currentTimeMillis() - start);

                // 方案1：先尝试段落提取（轻量级）
                StringBuilder text = new StringBuilder();
                int paragraphCount = 0;

                List<XWPFParagraph> paragraphs = doc.getParagraphs();
                for (XWPFParagraph para : paragraphs) {
                    String paraText = para.getText();
                    if (paraText != null && !paraText.trim().isEmpty()) {
                        text.append(paraText).append("\n");
                        paragraphCount++;
                    }

                    // 限制最大段落数，防止死循环
                    if (paragraphCount > 1000) {
                        log.warn("段落数超过1000，停止继续解析");
                        break;
                    }
                }

                // 如果段落提取有内容，直接返回
                if (!text.isEmpty()) {
                    log.info("段落提取完成，共 {} 段，长度: {}，耗时: {} ms",
                            paragraphCount, text.length(), System.currentTimeMillis() - start);
                    return text.toString();
                }

                // 方案2：段落提取为空，尝试完整提取
                log.warn("段落提取为空，尝试使用完整提取");
                XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
                String fullText = extractor.getText();

                if (fullText != null && !fullText.isEmpty()) {
                    log.info("完整提取完成，长度: {}，耗时: {} ms",
                            fullText.length(), System.currentTimeMillis() - start);
                    return fullText;
                }

                // 方案3：都失败了，返回友好提示
                return "【文档内容为空或无法提取】";

            } catch (Exception e) {
                log.error("DOCX解析异常", e);
                return "【文件解析失败，请尝试使用纯文本格式】";
            }
        });

        try {
            // ⏱️ 超时保护：15秒超时
            return future.get(15, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("DOCX解析超时，文件可能过大或格式复杂");
            return "【文件解析超时，请尝试使用纯文本格式或较小的文件】";

        } catch (Exception e) {
            log.error("DOCX解析异常", e);
            return "【文件解析异常】";

        } finally {
            executor.shutdownNow();
        }
    }

    private String extractTextFromTxtFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}