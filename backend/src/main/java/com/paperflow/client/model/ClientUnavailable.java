package com.paperflow.client.model;

public final class ClientUnavailable extends RuntimeException {
    private final String code;

    public ClientUnavailable(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
