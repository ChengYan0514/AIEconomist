package com.paperflow.paper.model;

public final class PaperNotFound extends RuntimeException {
    public PaperNotFound() {
        super("找不到该论文");
    }
}
