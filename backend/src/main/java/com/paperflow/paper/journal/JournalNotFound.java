package com.paperflow.paper.journal;

public class JournalNotFound extends RuntimeException {
    public JournalNotFound() {
        super("找不到对应的期刊数据");
    }
}
