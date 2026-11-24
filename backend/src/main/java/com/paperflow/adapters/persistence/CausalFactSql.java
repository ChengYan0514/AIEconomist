package com.paperflow.adapters.persistence;

import com.paperflow.causal.graph.CausalFactReader.*;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class CausalFactSql {
    private static final String NULL_LIST = "('null_efffect','null','null_effect','null effect')";
    static final String EDGE_SQL = """
            SELECT ct.claim_id, ct.cause_standard, ct.effect_standard,
                   COUNT(*) AS record_count, COUNT(DISTINCT pct.paper_id) AS paper_count,
                   COUNT(DISTINCT NULLIF(TRIM(pct.causal_inference_method), '')) AS diversity,
                   COUNT(*) FILTER (WHERE lower(trim(pct.sign_of_impact)) = 'positive') AS positive_count,
                   COUNT(*) FILTER (WHERE lower(trim(pct.sign_of_impact)) = 'negative') AS negative_count,
                   COUNT(*) FILTER (WHERE lower(trim(pct.sign_of_impact)) IN """ + NULL_LIST + """
            ) AS null_count,
                   COUNT(*) FILTER (WHERE pct.sign_of_impact IS NULL
                       OR lower(trim(pct.sign_of_impact)) NOT IN ('positive','negative','null_efffect','null','null_effect','null effect')) AS mixed_count,
                   ARRAY_REMOVE(ARRAY_AGG(DISTINCT NULLIF(TRIM(pct.causal_inference_method), '')), NULL) AS methods,
                   ARRAY_AGG(DISTINCT pct.paper_id) AS work_ids
            FROM claim_table ct JOIN paper_claim_table pct ON pct.claim_id = ct.claim_id
            """;
    static final String CLAIM_SQL = """
            SELECT pct.record_id, pct.paper_id AS work_id, ct.claim_id, pct.claim, pct.cause, pct.effect,
                   ct.cause_standard, ct.effect_standard, pct.sign_of_impact, pct.type_of_relationship,
                   pct.causal_inference_method, pct.evidence_method_other_description, pct.is_main_contribution,
                   pct.level_of_tentativeness, pct.sources_of_exogenous_variation, pct.statistical_significance,
                   pct.cause_score, pct.effect_score, pct.evidence
            FROM paper_claim_table pct JOIN claim_table ct ON ct.claim_id = pct.claim_id
            """;
    static final RowMapper<EdgeAggregate> EDGE_ROW = (rs, i) -> new EdgeAggregate(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
            rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
            stringList(rs, 11).stream().sorted().toList(), stringList(rs, 12));
    static final RowMapper<RawClaimRow> CLAIM_ROW = (rs, i) -> new RawClaimRow(
            rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
            (Boolean) rs.getObject(13), rs.getString(14), rs.getString(15), rs.getString(16),
            rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getString(19));

    private CausalFactSql() {}

    static String escapeLike(String query) {
        return query.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    static List<String> stringList(ResultSet rs, int index) throws SQLException {
        Array array = rs.getArray(index);
        if (array == null) return List.of();
        return array.getArray() instanceof String[] items
                ? Arrays.stream(items).filter(s -> s != null && !s.isBlank()).toList() : List.of();
    }
}
