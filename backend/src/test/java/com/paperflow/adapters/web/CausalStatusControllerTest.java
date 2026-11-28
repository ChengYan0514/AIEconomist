package com.paperflow.adapters.web;

import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.causal.graph.CausalFactReader.NamedCountRow;
import com.paperflow.causal.graph.CausalFactReader.PaperSummaryRow;
import com.paperflow.causal.model.CausalOverviewStats;
import com.paperflow.causal.model.IngestionMode;
import com.paperflow.main.CausalProperties;
import com.paperflow.paper.model.PaperNotFound;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.net.URI;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CausalStatusControllerTest {
    private static CausalProperties properties(String dbHost) {
        return new CausalProperties(true, IngestionMode.RAW, URI.create("http://127.0.0.1:18003"),
                Duration.ofMinutes(5), 2, 100, 50, Duration.ofHours(1),
                "", dbHost, "", "", "", "", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void localOverviewStatsBindsSourceIdAsParameter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CausalFactReader facts = mock(CausalFactReader.class);
        var expected = new CausalOverviewStats(3, 5, 1, 2, 14);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(expected);

        var controller = new CausalStatusController(jdbc, facts, properties(""));
        var stats = controller.overviewStats(" S123' OR '1'='1 ");

        assertThat(stats).isEqualTo(expected);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).queryForObject(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("AND p.source_id = ?").doesNotContain("OR '1'='1");
        assertThat(argsCaptor.getValue()).containsExactly("S123' OR '1'='1");
    }

    @Test
    void crossDatasourceOverviewStatsAggregatesClaimsAndRunsWithoutLocalClaimTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CausalFactReader facts = mock(CausalFactReader.class);
        when(facts.listPaperClaimCounts()).thenReturn(List.of(
                new NamedCountRow("W1", 4L),
                new NamedCountRow("W2", 2L),
                new NamedCountRow("W3", 3L)
        ));
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            emit(handler, "W1", "FAILED");        // claims > 0 beats FAILED -> succeeded
            emit(handler, "W2", "RUNNING");       // active beats claims > 0 -> active
            emit(handler, "W3", null);            // claims > 0 -> succeeded
            emit(handler, "W4", "SUCCEEDED");     // 0 claims + SUCCEEDED -> succeeded
            emit(handler, "W5", "FAILED");        // 0 claims + FAILED -> failed
            emit(handler, "W6", "CANCELLED");     // 0 claims + CANCELLED -> unextracted
            emit(handler, null, null);            // 0 claims + null -> unextracted
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        var controller = new CausalStatusController(jdbc, facts, properties("causal-db.internal"));
        var stats = controller.overviewStats(null);

        assertThat(stats.succeededPapers()).isEqualTo(3);
        assertThat(stats.activePapers()).isEqualTo(1);
        assertThat(stats.failedPapers()).isEqualTo(1);
        assertThat(stats.unextractedPapers()).isEqualTo(2);
        assertThat(stats.totalClaims()).isEqualTo(9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paperStatusReadsClaimCountFromCausalFactReaderAndThrowsWhenMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CausalFactReader facts = mock(CausalFactReader.class);
        when(facts.findPaperSummary("W009")).thenReturn(new PaperSummaryRow(5L, 3L, 4L));
        UUID batchId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq("p1"))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("paper_id")).thenReturn("p1");
            when(rs.getString("matched_work_id")).thenReturn("W009");
            when(rs.getString("source_id")).thenReturn("S123");
            when(rs.getString("title")).thenReturn("Paper One");
            when(rs.getString("fulltext_status")).thenReturn("PUBLISHED");
            when(rs.getString("run_status")).thenReturn("SUCCEEDED");
            when(rs.getObject("batch_id", UUID.class)).thenReturn(batchId);
            when(rs.getObject("run_id", UUID.class)).thenReturn(runId);
            return List.of(mapper.mapRow(rs, 0));
        });
        when(jdbc.query(anyString(), any(RowMapper.class), eq("missing"))).thenReturn(List.of());

        var controller = new CausalStatusController(jdbc, facts, properties("causal-db.internal"));
        var status = controller.paperStatus("p1");
        assertThat(status.status()).isEqualTo("EXTRACTED");
        assertThat(status.claimCount()).isEqualTo(5);
        assertThat(status.activeBatchId()).isEqualTo(batchId);
        assertThatThrownBy(() -> controller.paperStatus("missing")).isInstanceOf(PaperNotFound.class);
    }

    private static void emit(RowCallbackHandler handler, String workId, String runStatus) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("matched_work_id")).thenReturn(workId);
        when(rs.getString("last_run_status")).thenReturn(runStatus);
        handler.processRow(rs);
    }
}
