package com.paperflow.job;

public final class JobConflict extends RuntimeException {
    public JobConflict(String message) {
        super(message);
    }
}
