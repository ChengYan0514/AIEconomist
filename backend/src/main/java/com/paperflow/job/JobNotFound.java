package com.paperflow.job;

public final class JobNotFound extends RuntimeException {
    public JobNotFound() {
        super("找不到该任务");
    }
}
