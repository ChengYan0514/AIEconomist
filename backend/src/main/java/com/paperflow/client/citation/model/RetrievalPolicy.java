package com.paperflow.client.citation.model;

import com.paperflow.client.model.ClientInvalid;

import java.util.List;

public record RetrievalPolicy(List<String> sourceIds, Integer yearMin, Integer yearMax) {
    public static final RetrievalPolicy UNRESTRICTED = new RetrievalPolicy(null, null, null);

    public RetrievalPolicy {
        sourceIds = sourceIds == null ? List.of() : normalize(sourceIds);
        if (invalidYear(yearMin) || invalidYear(yearMax) || (yearMin != null && yearMax != null && yearMin > yearMax)) {
            throw new ClientInvalid("年份范围无效");
        }
    }

    private static boolean invalidYear(Integer year) {
        return year != null && (year < 1000 || year > 9999);
    }

    private static List<String> normalize(List<String> ids) {
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.strip().length() > 64)) {
            throw new ClientInvalid("出版来源列表无效");
        }
        return ids.stream().map(String::strip).distinct().toList();
    }
}
