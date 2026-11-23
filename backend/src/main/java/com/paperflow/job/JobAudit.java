package com.paperflow.job;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;

import java.util.HashMap;
import java.util.Map;

public final class JobAudit {
    private final AuditLog audit;

    public JobAudit(AuditLog audit) {
        this.audit = audit;
    }

    void submitted(String actor, String action, JobSummary job) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("jobKind", job.kind().name());
        summary.put("totalItems", job.total());
        summary.put("readyItems", job.ready());
        summary.put("queuedItems", job.queued());
        summary.put("failedItems", job.failed());
        summary.put("skippedItems", job.skipped());
        if (job.retryOf() != null) summary.put("retryOf", job.retryOf().toString());
        audit.record(actor, action, "JOB", job.jobId().toString(), AuditOutcome.SUCCEEDED, summary);
    }

    void checked(String actor, JobItem item) {
        if ("FAILED".equals(item.state())) {
            item(actor, "JOB_ITEM_CHECK", item, item.state(), "PREFLIGHT_FAILED", null);
        } else if ("SKIPPED".equals(item.state())) {
            item(actor, "JOB_ITEM_CHECK", item, item.state(), "NOT_ELIGIBLE", null);
        }
    }

    void executed(String actor, JobItem item, String state, String code, Long revision) {
        item(actor, "JOB_EXECUTE", item, state, code, revision);
        if (item.paperId() == null || "SKIPPED".equals(state)) return;
        switch (item.kind()) {
            case ORIGINAL_IMPORT -> paper(actor, "PAPER_IMPORT", item, state, code, revision);
            case PUBLISH -> paper(actor, "PAPER_PUBLISH", item, state, code, revision);
            default -> {}
        }
    }

    private void item(String actor, String action, JobItem item, String state, String code, Long revision) {
        Map<String, Object> summary = summary(item, state, code, revision);
        summary.put("jobKind", item.kind().name());
        if (item.paperId() != null) summary.put("paperId", item.paperId());
        audit.record(actor, action, "JOB", item.jobId().toString(), outcome(state), summary);
    }

    private void paper(String actor, String action, JobItem item, String state, String code, Long revision) {
        Map<String, Object> summary = summary(item, state, code, revision);
        summary.put("jobId", item.jobId().toString());
        audit.record(actor, action, "PAPER", item.paperId(), outcome(state), summary);
    }

    private Map<String, Object> summary(JobItem item, String state, String code, Long revision) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("itemSequence", item.sequence());
        summary.put("itemState", state);
        if (code != null) summary.put("code", code);
        if (revision != null) summary.put("revision", revision);
        return summary;
    }

    private AuditOutcome outcome(String state) {
        return "FAILED".equals(state) ? AuditOutcome.FAILED : AuditOutcome.SUCCEEDED;
    }
}
