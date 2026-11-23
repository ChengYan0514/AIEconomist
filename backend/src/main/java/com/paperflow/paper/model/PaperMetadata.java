package com.paperflow.paper.model;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

public record PaperMetadata(String sourceId, int year, String title, String authors, String doi, String url, String provider) {
    public PaperMetadata {
        sourceId = source(sourceId);
        if (year < 1000 || year > 9999) throw new IllegalArgumentException("发表年份应为四位数字");
        title = required(title, 2000, "论文标题");
        authors = required(displayAuthors(authors), 2000, "作者");
        doi = doi(doi);
        url = optional(url, 2000, "原始链接");
        provider = optional(provider, 255, "采集平台");
        if (url != null) {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("原始链接必须是 HTTP 或 HTTPS 地址");
            }
        }
    }

    public String paperId() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    identity(sourceId, year, title, authors).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static String identity(String sourceId, int year, String title, String authors) {
        return canonical(sourceId) + "\n" + year + "\n" + canonical(title) + "\n" + orderedAuthors(authors);
    }

    public static String orderedAuthors(String authors) {
        return Arrays.stream(displayAuthors(authors).split(";")).map(PaperMetadata::canonical)
                .collect(Collectors.joining(";"));
    }

    private static String displayAuthors(String authors) {
        if (authors == null) return "";
        return Arrays.stream(authors.split(";", -1)).map(PaperMetadata::display)
                .filter(author -> !author.isBlank()).collect(Collectors.joining(";"));
    }

    public static String source(String value) {
        String normalized = display(value).toUpperCase(Locale.ROOT);
        if (!normalized.matches("S[0-9]{1,24}")) throw new IllegalArgumentException("请输入有效的 OpenAlex 来源 ID");
        return normalized;
    }

    public static String doi(String value) {
        String normalized = canonical(value);
        for (String prefix : new String[]{"https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/", "doi:"}) {
            if (normalized.startsWith(prefix)) normalized = normalized.substring(prefix.length()).trim();
        }
        return optional(normalized, 500, "DOI");
    }

    public static String canonical(String value) {
        return display(value).toLowerCase(Locale.ROOT);
    }

    private static String display(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    private static String required(String value, int maxLength, String field) {
        String text = optional(value, maxLength, field);
        if (text == null) throw new IllegalArgumentException(field + "不能为空");
        return text;
    }

    private static String optional(String value, int maxLength, String field) {
        String text = display(value);
        if (text.length() > maxLength || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + "的长度或内容无效");
        }
        return text.isEmpty() ? null : text;
    }
}
