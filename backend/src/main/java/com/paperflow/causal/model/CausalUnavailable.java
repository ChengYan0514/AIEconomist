package com.paperflow.causal.model;

public final class CausalUnavailable extends RuntimeException {
    private final String code;

    public CausalUnavailable(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
