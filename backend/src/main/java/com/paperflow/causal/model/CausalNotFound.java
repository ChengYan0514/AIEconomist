package com.paperflow.causal.model;

public final class CausalNotFound extends RuntimeException {
    private final String code;

    public CausalNotFound(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
