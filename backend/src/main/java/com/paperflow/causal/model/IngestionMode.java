package com.paperflow.causal.model;

public enum IngestionMode {
    RAW,
    STANDARDIZED;

    public static IngestionMode parse(String value, IngestionMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "RAW", "PR_COMPAT" -> RAW;
            case "STANDARDIZED" -> STANDARDIZED;
            default -> throw new IllegalArgumentException("无效的入库模式：" + value);
        };
    }

    public String engineMode() {
        return this == STANDARDIZED ? "STANDARDIZED" : "PR_COMPAT";
    }
}
