package com.paperflow.paper.model;

public final class DuplicatePaper extends PaperConflict {
    private final String paperId;

    public DuplicatePaper(String paperId) {
        super("DUPLICATE_PAPER", "该论文已经登记，请在详情页替换原件");
        this.paperId = paperId;
    }

    public String paperId() {
        return paperId;
    }
}
