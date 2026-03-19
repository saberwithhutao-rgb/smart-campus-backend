package com.smartcampus.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /**
     * 从 MultipartFile 提取文本（用于直接上传）
     */
    public String extractTextFromFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String extension = getFileExtension(filename);

        try {
            return switch (extension.toLowerCase()) {
                case "pdf" -> extractTextFromPdf(file);
                case "docx" -> extractTextFromDocx(file);
                case "txt" -> extractTextFromTxt(file);
                case "jpg", "jpeg", "png" -> "图片文件，待集成OCR功能";
                case "mp3", "wav" -> "语音文件，待集成ASR功能";
                default -> throw new UnsupportedOperationException("不支持的文件格式: " + extension);
            };
        } catch (Exception e) {
            log.error("文件解析失败: {}", filename, e);
            return "【文件解析失败: " + e.getMessage() + "】";
        }
    }

    /**
     * 存储文件到服务器
     */
    public String storeFile(MultipartFile file, String taskId) {
        String filename = taskId + "_" + file.getOriginalFilename();

        try {
            if (filename.contains("..")) {
                throw new RuntimeException("文件名包含非法路径序列: " + filename);
            }

            Path targetLocation = this.fileStorageLocation.resolve(filename);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件保存成功: {}, 大小: {} KB", filename, file.getSize() / 1024);

            return targetLocation.toString();
        } catch (IOException ex) {
            log.error("文件存储失败: {}", filename, ex);
            throw new RuntimeException("无法存储文件: " + filename, ex);
        }
    }

    /**
     * 从已保存的文件路径提取文本内容
     */
    public String extractTextFromFileByPath(String filePath) {
        File file = new File(filePath);
        log.info("开始解析文件: {}", filePath);

        if (!file.exists()) {
            log.error("文件不存在: {}", filePath);
            return "【文件不存在】";
        }

        String filename = file.getName();
        String extension = getFileExtension(filename);
        long fileSizeKB = file.length() / 1024;
        log.info("文件大小: {} KB, 格式: {}", fileSizeKB, extension);

        try {
            return switch (extension.toLowerCase()) {
                case "pdf" -> extractTextFromPdfFile(file);
                case "docx" -> extractTextFromDocxFile(file);
                case "txt" -> extractTextFromTxtFile(file);
                default -> "【不支持的文件格式: " + extension + "】";
            };
        } catch (Exception e) {
            log.error("文件解析失败: {}", filename, e);
            return "【文件解析失败: " + e.getMessage() + "】";
        }
    }

    /**
     * 解析 PDF 文件（直接上传）
     */
    private String extractTextFromPdf(MultipartFile file) {
        long start = System.currentTimeMillis();
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            log.info("PDF解析完成，长度: {}，耗时: {} ms", text.length(), System.currentTimeMillis() - start);
            return text;
        } catch (Exception e) {
            log.error("PDF解析失败", e);
            return "【PDF解析失败: " + e.getMessage() + "】";
        }
    }

    /**
     * 解析 PDF 文件（从路径）
     */
    private String extractTextFromPdfFile(File file) {
        long start = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try (PDDocument document = PDDocument.load(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(document);
            }
        });

        try {
            String result = future.get(30, TimeUnit.SECONDS); // PDF 可能更大，给 30 秒
            log.info("PDF解析完成，长度: {}，耗时: {} ms", result.length(), System.currentTimeMillis() - start);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("PDF解析超时");
            return "【PDF解析超时，文件可能过大或包含复杂内容】";
        } catch (Exception e) {
            log.error("PDF解析失败", e);
            return "【PDF解析失败】";
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 解析 DOCX 文件（直接上传）
     */
    private String extractTextFromDocx(MultipartFile file) {
        long start = System.currentTimeMillis();
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
            String text = extractor.getText();
            log.info("DOCX解析完成，长度: {}，耗时: {} ms", text.length(), System.currentTimeMillis() - start);
            return text;
        } catch (Exception e) {
            log.error("DOCX解析失败", e);
            return "【DOCX解析失败: " + e.getMessage() + "】";
        }
    }

    /**
     * 解析 DOCX 文件（从路径）- 带超时保护和范围限制
     */
    private String extractTextFromDocxFile(File file) {
        log.info("开始解析 DOCX 文件，大小: {} KB", file.length() / 1024);
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try (FileInputStream fis = new FileInputStream(file);
                 XWPFDocument doc = new XWPFDocument(fis)) {

                log.info("XWPFDocument 加载完成，耗时: {} ms", System.currentTimeMillis() - start);

                // 方案1：段落提取（轻量级）
                StringBuilder text = new StringBuilder();
                int paragraphCount = 0;

                List<XWPFParagraph> paragraphs = doc.getParagraphs();
                for (XWPFParagraph para : paragraphs) {
                    String paraText = para.getText();
                    if (paraText != null && !paraText.trim().isEmpty()) {
                        text.append(paraText).append("\n");
                        paragraphCount++;
                    }

                    if (paragraphCount > 1000) {
                        log.warn("段落数超过1000，停止解析");
                        break;
                    }
                }

                if (!text.isEmpty()) {
                    log.info("段落提取完成，共 {} 段，长度: {}，耗时: {} ms",
                            paragraphCount, text.length(), System.currentTimeMillis() - start);
                    return text.toString();
                }

                // 方案2：完整提取
                log.warn("段落提取为空，尝试完整提取");
                XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
                String fullText = extractor.getText();

                if (fullText != null && !fullText.isEmpty()) {
                    log.info("完整提取完成，长度: {}，耗时: {} ms",
                            fullText.length(), System.currentTimeMillis() - start);
                    return fullText;
                }

                return "【文档内容为空】";

            } catch (Exception e) {
                log.error("DOCX解析异常", e);
                return "【DOCX解析失败】";
            }
        });

        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("DOCX解析超时");
            return "【DOCX解析超时，请尝试使用纯文本格式】";
        } catch (Exception e) {
            log.error("DOCX解析异常", e);
            return "【DOCX解析异常】";
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 解析 TXT 文件（直接上传）
     */
    private String extractTextFromTxt(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("TXT解析失败", e);
            return "【TXT解析失败】";
        }
    }

    /**
     * 解析 TXT 文件（从路径）
     */
    private String extractTextFromTxtFile(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            log.error("TXT解析失败", e);
            return "【TXT解析失败】";
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    /**
     * 检查是否支持的文件类型
     */
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
}