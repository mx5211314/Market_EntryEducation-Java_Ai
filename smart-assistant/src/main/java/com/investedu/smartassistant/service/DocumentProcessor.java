package com.investedu.smartassistant.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档处理器：PDF/HTML 解析、语义分块（标题回溯 + 跨页条款合并）
 */
@Service
public class DocumentProcessor {

    private final DocumentSplitter splitter = new DocumentByParagraphSplitter(512, 128);

    // 提取条款编号的正则
    private static final Pattern RULE_PATTERN = Pattern.compile("第[\\d一二三四五六七八九十百]+条");

    // 标题模式：第X章、X.X、X.X.X、(一)、1.、① 等
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^(?:第[\\d一二三四五六七八九十百千万]+章|[\\d一二三四五六七八九十百]+[.、.\\s]" +
            "|[（(][\\d一二三四五六七八九十百]+[）)]|[①②③④⑤⑥⑦⑧⑨⑩]|\\d+\\.\\d+(?:\\.\\d+)?)",
            Pattern.MULTILINE
    );

    // 风险等级关键词
    private static final Set<String> RED_LINE_WORDS = Set.of(
            "强制平仓", "红线", "禁止", "不得", "严重违规", "刑事责任"
    );

    // 分隔符模式（用于检测跨页断句）
    private static final Pattern PAGE_BREAK_PATTERN = Pattern.compile("\\f|\\n\\s*\\n\\s*第\\d+页");

    public List<TextSegment> loadAndSplit(File file, Map<String, String> baseMetadata) throws IOException {
        String text;
        if (file.getName().endsWith(".pdf")) {
            PDDocument pdDoc = PDDocument.load(file);
            text = new PDFTextStripper().getText(pdDoc);
            pdDoc.close();
        } else {
            text = new String(Files.readAllBytes(file.toPath()));
        }
        return split(text, file.getName(), baseMetadata);
    }

    /**
     * 切分内存中的文本，供后台文章入库使用。
     * 文章正文可能是 wangEditor 的 HTML，标签必须先剥掉：否则切片里塞满 <p>、style 属性，
     * 嵌入向量被噪声带偏，检索出来的片段也没法直接给用户看。
     */
    public List<TextSegment> splitText(String rawText, String source, Map<String, String> baseMetadata) {
        String text = stripHtml(rawText);
        if (text.isBlank()) return List.of();
        return split(text, source, baseMetadata);
    }

    private List<TextSegment> split(String text, String source, Map<String, String> baseMetadata) {
        // 1. 预处理：标题回溯注入、跨页条款合并
        String processedText = preprocessText(text);

        // 2. 基础段落切分
        Document doc = Document.from(processedText, new dev.langchain4j.data.document.Metadata(baseMetadata));
        List<TextSegment> segments = splitter.split(doc);

        // 3. 后处理：元数据增强、条款合并、去重
        return postProcessSegments(segments, source, baseMetadata);
    }

    /**
     * 预处理：标题回溯 + 跨页条款合并
     */
    private String preprocessText(String text) {
        // 移除页码干扰
        String cleaned = PAGE_BREAK_PATTERN.matcher(text).replaceAll("\n\n");

        // 标题回溯：为每段文本注入最近的标题路径
        return injectTitlePath(cleaned);
    }

    /**
     * 标题回溯：为每个段落前缀加上所属的标题层级路径
     * 例："第三节 风险控制\n第一款 公司应当...\n第二款 ..."
     * -> 段落获得 metadata: {title_path: "第三节 风险控制 > 第一款"}
     */
    private String injectTitlePath(String text) {
        String[] lines = text.split("\n");
        StringBuilder result = new StringBuilder();
        Deque<String> titleStack = new ArrayDeque<>(); // 维护标题层级栈

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                result.append("\n");
                continue;
            }

            Matcher matcher = TITLE_PATTERN.matcher(line);
            if (matcher.lookingAt()) {
                // 是标题行：更新栈
                String title = line.split("[\\s\\n]")[0];
                int level = estimateTitleLevel(line);

                // 弹出同级或更低级标题
                while (!titleStack.isEmpty() && estimateTitleLevel(titleStack.peek()) >= level) {
                    titleStack.pop();
                }
                titleStack.push(title);
            }

            // 构建标题路径
            String titlePath = titleStack.stream()
                    .collect(Collectors.joining(" > "));

            // 注入元数据标记（后处理时解析）
            if (!titlePath.isEmpty()) {
                result.append("[TITLE_PATH:").append(titlePath).append("] ");
            }
            result.append(lines[i]).append("\n");
        }
        return result.toString();
    }

    private int estimateTitleLevel(String line) {
        if (line.contains("章")) return 1;
        if (line.contains("节")) return 2;
        if (line.matches(".*\\d+\\.\\d+\\.\\d+.*")) return 4;
        if (line.matches(".*\\d+\\.\\d+.*")) return 3;
        if (line.matches(".*[（(][一二三四五六七八九十][）)].*")) return 4;
        if (line.matches(".*[①②③④⑤⑥⑦⑧⑨⑩].*")) return 4;
        return 3;
    }

    /**
     * 后处理：解析 TITLE_PATH、合并同条款碎片、元数据增强
     */
    private List<TextSegment> postProcessSegments(List<TextSegment> segments, String source, Map<String, String> baseMetadata) {
        // 1. 解析并移除 TITLE_PATH 标记，存入 metadata
        List<TextSegment> processed = new ArrayList<>();
        for (TextSegment seg : segments) {
            String content = seg.text();
            Matcher tpMatcher = Pattern.compile("\\[TITLE_PATH:([^\\]]+)\\]\\s*").matcher(content);
            String titlePath = "";
            if (tpMatcher.find()) {
                titlePath = tpMatcher.group(1);
                content = tpMatcher.replaceFirst("");
            }
            // 提取条款编号
            String ruleId = "";
            Matcher ruleMatcher = RULE_PATTERN.matcher(content);
            if (ruleMatcher.find()) {
                ruleId = ruleMatcher.group();
            }
            // 风险等级标签
            String riskLevel = "普通";
            for (String word : RED_LINE_WORDS) {
                if (content.contains(word)) {
                    riskLevel = "红线";
                    break;
                }
            }
            // 构建新 metadata
            var metadata = new dev.langchain4j.data.document.Metadata();
            if (!titlePath.isEmpty()) metadata.put("title_path", titlePath);
            if (!ruleId.isEmpty()) metadata.put("rule_id", ruleId);
            metadata.put("risk_level", riskLevel);
            metadata.put("source", source);
            for (Map.Entry<String, String> entry : baseMetadata.entrySet()) {
                metadata.put(entry.getKey(), entry.getValue());
            }
            processed.add(TextSegment.from(content.trim(), metadata));
        }

        // 2. 合并同一条款的相邻碎片（跨页/跨段落条款合并）
        return mergeSameRuleSegments(processed);
    }

    /**
     * 合并同一 rule_id 的相邻段落，避免条款被切碎
     */
    private List<TextSegment> mergeSameRuleSegments(List<TextSegment> segments) {
        if (segments.size() <= 1) return segments;

        List<TextSegment> result = new ArrayList<>();
        TextSegment current = segments.get(0);

        for (int i = 1; i < segments.size(); i++) {
            TextSegment next = segments.get(i);
            String curRule = current.metadata().getString("rule_id");
            String nextRule = next.metadata().getString("rule_id");

            // 同一条款且都非空 -> 合并
            if (curRule != null && !curRule.isEmpty() && curRule.equals(nextRule)) {
                String mergedText = current.text() + "\n" + next.text();
                current = TextSegment.from(mergedText, current.metadata());
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    private String stripHtml(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre)\\s*>", "\n\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&", "&")
                .replace("<", "<")
                .replace(">", ">")
                .replace("\"", "\"")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return s;
    }
}