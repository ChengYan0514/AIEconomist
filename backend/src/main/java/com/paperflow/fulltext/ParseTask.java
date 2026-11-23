package com.paperflow.fulltext;

public record ParseTask(String taskId, String fileId, boolean uploaded) {
    public ParseTask {
        if (taskId == null || fileId == null || !taskId.matches("[a-zA-Z0-9_-]{1,128}")
                || !fileId.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("解析服务返回了无效的任务或文件 ID");
        }
    }

    public ParseTask afterUpload() {
        return new ParseTask(taskId, fileId, true);
    }
}
