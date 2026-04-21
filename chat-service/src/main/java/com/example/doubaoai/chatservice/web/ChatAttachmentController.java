package com.example.doubaoai.chatservice.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 上传附件并提取纯文本，供前端拼入用户消息后由模型回答。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatAttachmentController {

    private static final long MAX_BYTES = 5 * 1024 * 1024;

    @PostMapping(value = "/attachments/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> extract(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件过大（最大 5MB）");
        }
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        String text;
        if (lower.endsWith(".pdf")) {
            text = extractPdf(file.getInputStream());
        }
        else if (lower.endsWith(".docx")) {
            text = extractDocx(file.getInputStream());
        }
        else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".log") || lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的格式，请上传 pdf、docx 或 txt/md/json 等纯文本类文件");
        }
        String cleaned = text == null ? "" : text.strip();
        if (cleaned.length() > 400_000) {
            cleaned = cleaned.substring(0, 400_000) + "\n…（内容已截断）";
        }
        if (cleaned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未能从文件中解析出文本内容");
        }
        return Map.of("fileName", name, "text", cleaned);
    }

    private static String extractPdf(InputStream in) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    /**
     * 简易 docx 解析：读取 word/document.xml 并去掉标签（演示用，复杂排版可能不完美）。
     */
    private static String extractDocx(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    byte[] all = zis.readAllBytes();
                    String xml = new String(all, StandardCharsets.UTF_8);
                    out.append(xml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip());
                    break;
                }
            }
        }
        return out.toString();
    }
}
