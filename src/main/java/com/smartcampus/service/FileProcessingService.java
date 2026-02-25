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
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
            return extractor.getText();
        }
    }

    private String extractTextFromTxtFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}