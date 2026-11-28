package com.paperflow.adapters.web;

import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.causal.model.CausalOverviewStats;
import com.paperflow.causal.model.PaperCausalStatus;
import com.paperflow.main.CausalProperties;
import com.paperflow.paper.model.CausalStatus;
import com.paperflow.paper.model.PaperNotFound;
import com.paperflow.paper.model.PaperStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/causal")
public class CausalStatusController {
    private static final String OVERVIEW_LOCAL_PREFIX = """
            WITH claimed_works AS (
                SELECT paper_id AS work_id, COUNT(*) AS claim_count
                FROM paper_claim_table
                GROUP BY paper_id
            ),
            latest_runs AS (
                SELECT DISTINCT ON (paper_id) paper_id, status
                FROM v2_causal_run
                ORDER BY paper_id, updated_at DESC
            ),
            paper_status AS (
                SELECT COALESCE(cw.claim_count, 0) AS claims,
                       lr.status AS last_run_status
                FROM v2_paper p
                LEFT JOIN claimed_works cw ON cw.work_id = p.matched_work_id
                LEFT JOIN latest_runs lr ON lr.paper_id = p.paper_id
                WHERE p.archived_at IS NULL
            """;
    private static final String OVERVIEW_LOCAL_SUFFIX = """
            )
            SELECT
                COUNT(*) FILTER (
                    WHERE COALESCE(last_run_status, '') NOT IN ('QUEUED', 'RUNNING', 'PENDING_REVIEW', 'PUBLISHING')
                      AND (claims > 0 OR last_run_status = 'SUCCEEDED')
                ) AS succeeded_papers,
                COUNT(*) FILTER (
                    WHERE last_run_status IN ('QUEUED', 'RUNNING', 'PENDING_REVIEW', 'PUBLISHING')
                ) AS active_papers,
                COUNT(*) FILTER (
                    WHERE claims = 0 AND last_run_status = 'FAILED'
                ) AS failed_papers,
                COUNT(*) FILTER (
                    WHERE claims = 0 AND (last_run_status IS NULL OR last_run_status IN ('SKIPPED', 'CANCELLED'))
                ) AS unextracted_papers,
                COALESCE(SUM(claims), 0) AS total_claims
            FROM paper_status
            """;
    private static final String OVERVIEW_REMOTE_SQL = """
            WITH latest_runs AS (
                SELECT DISTINCT ON (paper_id) paper_id, status
                FROM v2_causal_run
                ORDER BY paper_id, updated_at DESC
            )
            SELECT p.matched_work_id, lr.status AS last_run_status
            FROM v2_paper p
            LEFT JOIN latest_runs lr ON lr.paper_id = p.paper_id
            WHERE p.archived_at IS NULL
            """;
    private static final String PAPER_STATUS_SQL = """
            SELECT p.paper_id, p.matched_work_id, p.source_id, p.title, p.status AS fulltext_status,
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

    private final JdbcTemplate jdbc;
    private final CausalFactReader causalFacts;
    private final boolean separateCausalDatasource;

    public CausalStatusController(JdbcTemplate jdbc, CausalFactReader causalFacts, CausalProperties causalProperties) {
        this.jdbc = jdbc;
        this.causalFacts = causalFacts;
        this.separateCausalDatasource = causalProperties.hasSeparateDatasource();
    }

    @GetMapping("/overview-stats")
    public CausalOverviewStats overviewStats(@RequestParam(required = false) String sourceId) {
        String normalizedSource = sourceId == null || sourceId.isBlank() ? null : sourceId.strip();
        if (separateCausalDatasource) {
            return overviewStatsCrossDatasource(normalizedSource);
        }
        String sql = OVERVIEW_LOCAL_PREFIX
                + (normalizedSource == null ? "" : " AND p.source_id = ? ")
                + OVERVIEW_LOCAL_SUFFIX;
        Object[] args = normalizedSource == null ? new Object[0] : new Object[]{normalizedSource};
        return jdbc.queryForObject(sql, (rs, i) -> new CausalOverviewStats(
                rs.getLong("succeeded_papers"),
                rs.getLong("unextracted_papers"),
                rs.getLong("failed_papers"),
                rs.getLong("active_papers"),
                rs.getLong("total_claims")
        ), args);
    }

    @GetMapping("/papers/status/{paperId}")
    public PaperCausalStatus paperStatus(@PathVariable String paperId) {
        var rows = jdbc.query(PAPER_STATUS_SQL, (rs, i) -> {
            String workId = rs.getString("matched_work_id");
            PaperStatus fulltextStatus = PaperStatus.valueOf(rs.getString("fulltext_status"));
            String runStatus = rs.getString("run_status");
            int claimCount = loadClaimCount(workId);
            CausalStatus status = CausalStatus.resolve(runStatus, claimCount, fulltextStatus, workId);
            return new PaperCausalStatus(
                    rs.getString("paper_id"), workId, rs.getString("source_id"), rs.getString("title"),
                    status.name(), claimCount, rs.getObject("batch_id", UUID.class),
                    rs.getObject("run_id", UUID.class), rs.getString("error_code"), rs.getString("error_message"));
        }, paperId);
        if (rows.isEmpty()) throw new PaperNotFound();
        return rows.get(0);
    }

    private int loadClaimCount(String workId) {
        if (workId == null || workId.isBlank()) return 0;
        var summary = causalFacts.findPaperSummary(workId);
        return summary == null ? 0 : Math.toIntExact(summary.claimRecordCount());
    }

    private CausalOverviewStats overviewStatsCrossDatasource(String normalizedSource) {
        Map<String, Long> claimCounts = new HashMap<>();
        for (var row : causalFacts.listPaperClaimCounts()) {
            if (row.name() != null && row.count() > 0) claimCounts.put(row.name(), row.count());
        }
        String sql = OVERVIEW_REMOTE_SQL + (normalizedSource == null ? "" : " AND p.source_id = ? ");
        Object[] args = normalizedSource == null ? new Object[0] : new Object[]{normalizedSource};
        long[] counts = new long[5];
        jdbc.query(sql, rs -> {
            String workId = rs.getString("matched_work_id");
            String runStatus = rs.getString("last_run_status");
            long claims = workId == null ? 0L : claimCounts.getOrDefault(workId, 0L);
            counts[4] += claims;
            if ("QUEUED".equals(runStatus) || "RUNNING".equals(runStatus)
                    || "PENDING_REVIEW".equals(runStatus) || "PUBLISHING".equals(runStatus)) {
                counts[3]++;
            } else if (claims > 0 || "SUCCEEDED".equals(runStatus)) {
                counts[0]++;
            } else if ("FAILED".equals(runStatus)) {
                counts[2]++;
            } else if (runStatus == null || "SKIPPED".equals(runStatus) || "CANCELLED".equals(runStatus)) {
                counts[1]++;
            }
        }, args);
        return new CausalOverviewStats(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }
}
