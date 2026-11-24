package com.paperflow.causal.model;

public final class CausalConflict extends RuntimeException {
    private final String code;

    public CausalConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
