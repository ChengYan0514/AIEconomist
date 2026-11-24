package com.paperflow.causal.model;

public enum RunStage {
    PREPARE,
    STAGE0,
    STAGE1,
    NORMALIZE,
    MAP,
    PUBLISH;

    public static RunStage fromEngine(String value) {
        if (value == null || value.isBlank()) return PREPARE;
        return switch (value.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "STAGE0" -> STAGE0;
            case "STAGE1" -> STAGE1;
            case "NORMALIZE" -> NORMALIZE;
            case "MAP" -> MAP;
            case "PUBLISH", "VALIDATE", "DONE" -> PUBLISH;
            default -> PREPARE;
        };
    }
}
