package com.paperflow.client.citation.model;

import com.paperflow.client.model.ClientInvalid;

public record CitationSubmission(String title, String text, CitationStyle style, RetrievalPolicy retrievalPolicy) {
    public CitationSubmission {
        if (text == null || text.isBlank() || length(text) > 20_000) {
            throw new ClientInvalid("正文长度必须在 1 到 20000 字符之间");
        }
        if (title != null && length(title) > 160) throw new ClientInvalid("任务标题不能超过 160 字符");
        title = title == null || title.isBlank() ? generatedTitle(text) : title.strip();
        retrievalPolicy = retrievalPolicy == null ? RetrievalPolicy.UNRESTRICTED : retrievalPolicy;
    }

    private static String generatedTitle(String text) {
        String trimmed = text.strip();
        int end = trimmed.offsetByCodePoints(0, Math.min(60, length(trimmed)));
        for (int index = 0; index < end; index++) {
            if (".!?。！？\n\r".indexOf(trimmed.charAt(index)) >= 0) {
                end = index + 1;
                break;
            }
        }
        return trimmed.substring(0, end).strip();
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
