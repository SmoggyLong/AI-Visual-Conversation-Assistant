package com.aivca.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 文档解析器 —— 支持 .md (YAML front matter) / .json / .txt。
 */
@Slf4j
public final class DocumentParser {

    private static final Pattern FRONT_MATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "json", "txt", "pdf");

    private static final ObjectMapper mapper = new ObjectMapper();

    private DocumentParser() {}

    /**
     * 解析文件 → 文档列表（.json 可能返回多个）。
     *
     * @param file 文件路径
     * @return 解析结果列表；不支持格式或无有效内容时返回空列表
     */
    public static List<ParsedDocument> parse(Path file) {
        String ext = extension(file);
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            log.debug("[PARSER] 跳过不支持格式: {}", file.getFileName());
            return List.of();
        }

        String sourceFile = file.getFileName().toString();

        try {
            // PDF 单独处理（二进制）
            if ("pdf".equals(ext)) {
                return parsePdf(file, sourceFile);
            }

            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                log.debug("[PARSER] 跳过空文件: {}", sourceFile);
                return List.of();
            }

            return switch (ext) {
                case "md"  -> parseMarkdown(raw, sourceFile);
                case "json" -> parseJson(raw, sourceFile);
                case "txt" -> parsePlainText(raw, sourceFile);
                default -> List.of();
            };
        } catch (IOException e) {
            log.warn("[PARSER] 文件读取失败: {} | {}", sourceFile, e.getMessage());
            return List.of();
        }
    }

    /** 生成文档唯一 ID */
    static String generateId(String title, String content) {
        String seed = title + content.substring(0, Math.min(200, content.length()));
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(seed.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    // ==================== 格式解析 ====================

    private static List<ParsedDocument> parseMarkdown(String raw, String sourceFile) {
        Matcher m = FRONT_MATTER.matcher(raw);
        if (m.matches()) {
            String fmBlock = m.group(1);
            String body = m.group(2).trim();
            String title = extractField(fmBlock, "title", stripExtension(sourceFile));
            DocType type = parseDocType(extractField(fmBlock, "type", "general"));
            List<String> keywords = parseKeywords(extractField(fmBlock, "keywords", ""));
            return List.of(new ParsedDocument(title, body, type, keywords, sourceFile));
        }
        // 无 front matter → title=文件名, type=GENERAL
        return parsePlainText(raw, sourceFile);
    }

    private static List<ParsedDocument> parseJson(String raw, String sourceFile) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (!root.isArray()) {
                log.warn("[PARSER] JSON 不是数组格式: {}", sourceFile);
                return List.of();
            }
            List<ParsedDocument> docs = new ArrayList<>();
            for (JsonNode node : root) {
                String title = node.has("title") ? node.get("title").asText() : sourceFile;
                String content = node.has("content") ? node.get("content").asText() : "";
                DocType type = parseDocType(node.has("type") ? node.get("type").asText() : "general");
                List<String> keywords = parseJsonKeywords(node);
                if (!content.isBlank()) {
                    docs.add(new ParsedDocument(title, content, type, keywords, sourceFile));
                }
            }
            return docs;
        } catch (Exception e) {
            log.warn("[PARSER] JSON 解析失败: {} | {}", sourceFile, e.getMessage());
            return List.of();
        }
    }

    private static List<ParsedDocument> parsePlainText(String raw, String sourceFile) {
        String title = stripExtension(sourceFile);
        return List.of(new ParsedDocument(title, raw.trim(), DocType.GENERAL, List.of(), sourceFile));
    }

    private static List<ParsedDocument> parsePdf(Path file, String sourceFile) {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                log.info("[PARSER] PDF 无文本内容: {}", sourceFile);
                return List.of();
            }
            String title = stripExtension(sourceFile);
            log.info("[PARSER] PDF 解析成功 | {} → {} 字", sourceFile, text.length());
            return List.of(new ParsedDocument(title, text.trim(), DocType.GENERAL, List.of(), sourceFile));
        } catch (Exception e) {
            log.warn("[PARSER] PDF 解析失败: {} | {}", sourceFile, e.getMessage());
            return List.of();
        }
    }

    // ==================== 辅助方法 ====================

    /** 从 front matter 提取字段值（简单行: key: value） */
    private static String extractField(String fmBlock, String key, String defaultValue) {
        for (String line : fmBlock.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith(key.toLowerCase() + ":")) {
                String value = trimmed.substring(key.length() + 1).trim();
                return value.isEmpty() ? defaultValue : value;
            }
        }
        return defaultValue;
    }

    private static DocType parseDocType(String s) {
        return "sop".equalsIgnoreCase(s.trim()) ? DocType.SOP : DocType.GENERAL;
    }

    private static List<String> parseKeywords(String s) {
        if (s.isEmpty()) return List.of();
        return Arrays.stream(s.split("[,，]"))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .toList();
    }

    private static List<String> parseJsonKeywords(JsonNode node) {
        if (!node.has("keywords")) return List.of();
        JsonNode kw = node.get("keywords");
        if (kw.isArray()) {
            List<String> result = new ArrayList<>();
            kw.forEach(k -> { if (k.isTextual()) result.add(k.asText().trim()); });
            return result;
        }
        if (kw.isTextual()) return parseKeywords(kw.asText());
        return List.of();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    // ==================== 数据类 ====================

    /** 解析后的文档（中间表示） */
    public record ParsedDocument(
            String title,
            String content,
            DocType type,
            List<String> keywords,
            String sourceFile
    ) {
        public String id() { return generateId(title, content); }
    }
}
