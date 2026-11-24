package com.paperflow.client.citation.model;

import com.paperflow.client.model.ClientInvalid;

public enum CitationStyle {
    APA("apa"), IEEE("ieee"), CHICAGO_AUTHOR_DATE("chicago-author-date"), MLA("mla");

    private final String id;

    CitationStyle(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static CitationStyle parse(String id) {
        for (var style : values()) {
            if (style.id.equals(id)) return style;
        }
        throw new ClientInvalid("不支持的引文样式");
    }
}
