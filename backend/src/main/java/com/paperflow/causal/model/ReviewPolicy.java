package com.paperflow.causal.model;

public enum ReviewPolicy {
    MANUAL_REVIEW,
    AUTO_PUBLISH;

    public static ReviewPolicy parse(String value, ReviewPolicy fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "MANUAL", "MANUAL_REVIEW" -> MANUAL_REVIEW;
            case "AUTO", "AUTO_PUBLISH" -> AUTO_PUBLISH;
            default -> throw new IllegalArgumentException("无效的审核策略：" + value);
        };
    }
}
