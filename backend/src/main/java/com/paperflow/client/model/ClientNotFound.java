package com.paperflow.client.model;

public final class ClientNotFound extends RuntimeException {
    private final String code;

    public ClientNotFound(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
