package com.smartcampus.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
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

            initTesseract();

        } catch (Exception ex) {
            log.error("初始化失败", ex);
            throw new RuntimeException("无法创建文件上传目录", ex);
        }
    }

    /**
     * 初始化 Tesseract OCR
     */
    private void initTesseract() {
        // OCR引擎
        Tesseract tesseract;
        try {
            tesseract = new Tesseract();
            tesseract.setDatapath("/usr/share/tesseract-ocr/4.00/tessdata");
            tesseract.setLanguage("chi_sim+eng"); // 中文简体+英文
            tesseract.setPageSegMode(1);
            tesseract.setOcrEngineMode(1);
            log.info("Tesseract OCR 初始化成功");
        } catch (Exception e) {
            log.error("Tesseract 初始化失败，OCR功能将不可用", e);
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
                case "doc" -> extractTextFromDoc(file);
                case "txt" -> extractTextFromTxt(file);
                case "xls", "xlsx" -> extractTextFromExcel(file);
                case "pptx" -> extractTextFromPptx(file);
                default -> "【不支持的文件格式: " + extension + "】";
            };
        } catch (Exception e) {
            log.error("文件解析失败: {}", filename, e);
            return "【文件解析失败: " + e.getMessage() + "】";
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
                case "doc" -> extractTextFromDocFile(file);
                case "txt" -> extractTextFromTxtFile(file);
                case "xls", "xlsx" -> extractTextFromExcelFile(file);
                case "pptx" -> extractTextFromPptxFile(file);
                case "jpg", "jpeg", "png", "bmp", "gif" -> extractTextFromImageFile(file);
                default -> "【不支持的文件格式: " + extension + "】";
            };
        } catch (Exception e) {
            log.error("文件解析失败: {}", filename, e);
            return "【文件解析失败: " + e.getMessage() + "】";
        }
    }

    // ==================== PPTX 解析 ====================

    private String extractTextFromPptx(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             XMLSlideShow ppt = new XMLSlideShow(is)) {
            StringBuilder text = new StringBuilder();
            List<XSLFSlide> slides = ppt.getSlides();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                text.append("=== 第 ").append(i + 1).append(" 页 ===\n");

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        text.append(((XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                text.append("\n");
            }
            return text.toString();
        } catch (Exception e) {
            log.error("PPTX解析失败", e);
            return "【PPTX解析失败】";
        }
    }

    private String extractTextFromPptxFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            StringBuilder text = new StringBuilder();
            List<XSLFSlide> slides = ppt.getSlides();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                text.append("=== 第 ").append(i + 1).append(" 页 ===\n");

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        text.append(((XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                text.append("\n");
            }
            return text.toString();
        } catch (Exception e) {
            log.error("PPTX解析失败", e);
            return "【PPTX解析失败】";
        }
    }

    // ==================== Excel 解析 ====================

    private String extractTextFromExcel(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            Workbook workbook;
            String filename = file.getOriginalFilename();

            if (filename != null && filename.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(is);
            } else {
                workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook(is);
            }

            StringBuilder text = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                text.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        switch (cell.getCellType()) {
                            case STRING:
                                text.append(cell.getStringCellValue()).append("\t");
                                break;
                            case NUMERIC:
                                if (DateUtil.isCellDateFormatted(cell)) {
                                    text.append(cell.getDateCellValue()).append("\t");
                                } else {
                                    text.append(cell.getNumericCellValue()).append("\t");
                                }
                                break;
                            case BOOLEAN:
                                text.append(cell.getBooleanCellValue()).append("\t");
                                break;
                            case FORMULA:
                                try {
                                    text.append(cell.getCellFormula()).append("\t");
                                } catch (Exception e) {
                                    text.append("公式\t");
                                }
                                break;
                            default:
                                text.append(" \t");
                        }
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
            workbook.close();
            return text.toString();
        } catch (Exception e) {
            log.error("Excel解析失败", e);
            return "【Excel解析失败: " + e.getMessage() + "】";
        }
    }

    private String extractTextFromExcelFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            Workbook workbook;
            String filename = file.getName();

            if (filename.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis);
            } else {
                workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook(fis);
            }

            StringBuilder text = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                text.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        switch (cell.getCellType()) {
                            case STRING:
                                text.append(cell.getStringCellValue()).append("\t");
                                break;
                            case NUMERIC:
                                if (DateUtil.isCellDateFormatted(cell)) {
                                    text.append(cell.getDateCellValue()).append("\t");
                                } else {
                                    text.append(cell.getNumericCellValue()).append("\t");
                                }
                                break;
                            case BOOLEAN:
                                text.append(cell.getBooleanCellValue()).append("\t");
                                break;
                            case FORMULA:
                                try {
                                    text.append(cell.getCellFormula()).append("\t");
                                } catch (Exception e) {
                                    text.append("公式\t");
                                }
                                break;
                            default:
                                text.append(" \t");
                        }
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
            workbook.close();
            return text.toString();
        } catch (Exception e) {
            log.error("Excel解析失败", e);
            return "【Excel解析失败: " + e.getMessage() + "】";
        }
    }

    // ==================== DOC (旧版 Word) 解析 ====================

    private String extractTextFromDoc(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        } catch (Exception e) {
            log.error("DOC解析失败", e);
            return "【DOC解析失败】";
        }
    }

    private String extractTextFromDocFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        } catch (Exception e) {
            log.error("DOC解析失败", e);
            return "【DOC解析失败】";
        }
    }

    // ==================== 图片 OCR 解析 ====================

    private String extractTextFromImage(MultipartFile file) {
        return "【图片OCR功能已禁用，请上传PDF、Word、Excel、PPTX或TXT格式的文件】";
    }

    private String extractTextFromImageFile(File file) {
        return "【图片OCR功能已禁用，请上传PDF、Word、Excel、PPTX或TXT格式的文件】";
    }

    // ==================== 原有的 PDF、DOCX、TXT 等方法保持不变 ====================

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
            String result = future.get(30, TimeUnit.SECONDS);
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

    private String extractTextFromDocxFile(File file) {
        log.info("开始解析 DOCX 文件，大小: {} KB", file.length() / 1024);
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try (FileInputStream fis = new FileInputStream(file);
                 XWPFDocument doc = new XWPFDocument(fis)) {

                log.info("XWPFDocument 加载完成，耗时: {} ms", System.currentTimeMillis() - start);

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

    private String extractTextFromTxt(MultipartFile file) {
        String[] encodings = {"UTF-8", "GBK", "GB2312", "GB18030", "ISO-8859-1"};

        try {
            byte[] bytes = file.getBytes();
            for (String encoding : encodings) {
                try {
                    String content = new String(bytes, Charset.forName(encoding));
                } catch (Exception e) {
                }
            }
            return "【文件编码格式不支持，请转换为 UTF-8 格式后重试】";
        } catch (IOException e) {
            log.error("TXT解析失败", e);
            return "【TXT解析失败】";
        }
    }

    private String extractTextFromTxtFile(File file) {
        // 按优先级尝试多种编码
        String[] encodings = {"UTF-8", "GBK", "GB2312", "GB18030", "ISO-8859-1"};

        for (String encoding : encodings) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String content = new String(bytes, Charset.forName(encoding));
                log.info("成功使用编码 {} 解析文件: {}", encoding, file.getName());
                return content;

            } catch (UnsupportedEncodingException e) {
                log.warn("不支持的编码: {}", encoding);
            } catch (Exception e) {
                log.debug("使用编码 {} 解析失败: {}", encoding, e.getMessage());
            }
        }

        log.error("无法解析文件，尝试了所有编码: {}", file.getName());
        return "【文件编码格式不支持，请转换为 UTF-8 格式后重试】";
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
        String[] supported = {"pdf", "docx", "doc", "txt", "xls", "xlsx", "pptx", "jpg", "jpeg", "png", "bmp", "gif"};
        String extension = getFileExtension(filename).toLowerCase();

        for (String type : supported) {
            if (type.equals(extension)) {
                return true;
            }
        }
        return false;
    }
}