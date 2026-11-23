package com.paperflow.paper.model;

public final class PaperConflict extends RuntimeException {
    private final String code;

    public PaperConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
