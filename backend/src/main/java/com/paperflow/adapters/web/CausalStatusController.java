package com.paperflow.adapters.web;

import com.paperflow.causal.model.CausalOverviewStats;
import com.paperflow.causal.model.PaperCausalStatus;
import com.paperflow.paper.model.PaperNotFound;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/causal")
public class CausalStatusController {
    private final JdbcTemplate jdbc;

    public CausalStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview-stats")
    public CausalOverviewStats overviewStats(@RequestParam(required = false) String sourceId) {
        StringBuilder where = new StringBuilder(" WHERE NOT p.archived ");
        if (sourceId != null && !sourceId.isBlank()) {
            where.append(" AND p.source_id = '").append(sourceId.replace("'", "''")).append("' ");
        }

        String sql = """
                WITH paper_status AS (
                    SELECT p.paper_id,
                           p.status AS fulltext_status,
                           p.matched_work_id,
                           COALESCE((SELECT COUNT(*) FROM paper_claim_table pct WHERE pct.paper_id = p.matched_work_id), 0) AS claims,
                           (SELECT r.status FROM v2_causal_run r WHERE r.paper_id = p.paper_id ORDER BY r.updated_at DESC LIMIT 1) AS last_run_status
                    FROM v2_paper_read p
                """ + where + """
                )
                SELECT
                    COUNT(*) FILTER (
                        WHERE (claims > 0) OR (last_run_status = 'SUCCEEDED' AND claims = 0)
                    ) AS succeeded_papers,
                    COUNT(*) FILTER (
                        WHERE last_run_status IN ('QUEUED', 'RUNNING', 'PENDING_REVIEW', 'PUBLISHING')
                    ) AS active_papers,
                    COUNT(*) FILTER (
                        WHERE last_run_status = 'FAILED'
                    ) AS failed_papers,
                    COUNT(*) FILTER (
                        WHERE claims = 0 AND (last_run_status IS NULL OR last_run_status IN ('SKIPPED', 'CANCELLED'))
                    ) AS unextracted_papers,
                    COALESCE((
                        SELECT COUNT(*) FROM paper_claim_table pct
                        JOIN v2_paper_read p2 ON p2.matched_work_id = pct.paper_id
                """ + where.toString().replace("p.", "p2.") + """
                    ), 0) AS total_claims
                FROM paper_status;
                """;

        return jdbc.queryForObject(sql, (rs, i) -> new CausalOverviewStats(
                rs.getLong("succeeded_papers"),
                rs.getLong("unextracted_papers"),
                rs.getLong("failed_papers"),
                rs.getLong("active_papers"),
                rs.getLong("total_claims")
        ));
    }

    @GetMapping("/papers/status/{paperId}")
    public PaperCausalStatus paperStatus(@PathVariable String paperId) {
        String sql = """
                SELECT p.paper_id, p.matched_work_id, p.source_id, p.title, p.status AS fulltext_status,
                       COALESCE((SELECT COUNT(*) FROM paper_claim_table pct WHERE pct.paper_id = p.matched_work_id), 0) AS claim_count,
                       r.run_id, r.batch_id, r.status AS run_status, r.error_code, r.error_message
                FROM v2_paper_read p
                LEFT JOIN LATERAL (
                    SELECT run_id, batch_id, status, error_code, error_message
                    FROM v2_causal_run
                    WHERE paper_id = p.paper_id
                    ORDER BY updated_at DESC LIMIT 1
                ) r ON true
                WHERE p.paper_id = ?
                """;

        var rows = jdbc.query(sql, (rs, i) -> {
            String id = rs.getString("paper_id");
            String workId = rs.getString("matched_work_id");
            String sourceId = rs.getString("source_id");
            String title = rs.getString("title");
            String fulltextStatus = rs.getString("fulltext_status");
            int claimCount = rs.getInt("claim_count");
            UUID runId = rs.getObject("run_id", UUID.class);
            UUID batchId = rs.getObject("batch_id", UUID.class);
            String runStatus = rs.getString("run_status");
            String errorCode = rs.getString("error_code");
            String errorMessage = rs.getString("error_message");

            String status;
            if ("QUEUED".equals(runStatus) || "RUNNING".equals(runStatus) || "PUBLISHING".equals(runStatus)) {
                status = "EXTRACTING";
            } else if ("PENDING_REVIEW".equals(runStatus)) {
                status = "PENDING_REVIEW";
            } else if (claimCount > 0) {
                status = "EXTRACTED";
            } else if ("FAILED".equals(runStatus)) {
                status = "FAILED";
            } else if ("SUCCEEDED".equals(runStatus) && claimCount == 0) {
                status = "EXTRACTED_NO_CAUSAL";
            } else if (workId == null || workId.isBlank() || !"PUBLISHED".equals(fulltextStatus)) {
                status = "PREREQUISITE_MISSING";
            } else {
                status = "NOT_EXTRACTED";
            }

            return new PaperCausalStatus(id, workId, sourceId, title, status, claimCount, batchId, runId, errorCode, errorMessage);
        }, paperId);

        if (rows.isEmpty()) {
            throw new PaperNotFound();
        }
        return rows.get(0);
    }
}
