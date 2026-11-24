package com.paperflow.adapters.persistence;

import com.paperflow.causal.extraction.CausalExtractionModels.ClaimPayloadRow;
import com.paperflow.causal.extraction.CausalFactWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

public final class JdbcCausalFactWriter implements CausalFactWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcCausalFactWriter(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void checkNoLegacyDuplicates(String workId, List<ClaimPayloadRow> rows) {
        for (ClaimPayloadRow row : rows) {
            if (existingEvidenceIds(workId, row.cause(), row.effect()).size() > 1) {
                throw new IllegalStateException("LEGACY_DUPLICATE_RELATION: 历史存在重复关系键，已拒绝覆盖");
            }
        }
    }

    @Override
    public PublishOutcome publishIdempotently(UUID runId, String workId, String payloadHash, List<ClaimPayloadRow> rows) {
        String pubId = runId.toString();
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('causal_pub'), hashtext(?))", Object.class, pubId);
            List<String> receipts = jdbc.queryForList(
                    "SELECT payload_hash FROM causal_publication_receipt WHERE publication_id = ?", String.class, pubId);
            if (!receipts.isEmpty()) {
                if (!receipts.get(0).equals(payloadHash)) {
                    throw new IllegalStateException("PUBLICATION_HASH_CONFLICT: 同一发布键的载荷摘要冲突");
                }
                return new PublishOutcome(true, rows.size());
            }
            if (!rows.isEmpty()) {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('causal_facts'), hashtext(?))", Object.class, workId);
            }
            for (ClaimPayloadRow row : rows) upsertClaimAndEvidence(workId, row);
            jdbc.update("""
                    INSERT INTO causal_publication_receipt (publication_id, payload_hash, work_id, row_count)
                    VALUES (?, ?, ?, ?)
                    """, pubId, payloadHash, workId, rows.size());
            int bumped = jdbc.update("UPDATE causal_dataset_revision SET revision = revision + 1, updated_at = now() WHERE id = 1");
            if (bumped != 1) {
                jdbc.update("INSERT INTO causal_dataset_revision (id, revision) VALUES (1, 1) ON CONFLICT (id) DO UPDATE SET revision = causal_dataset_revision.revision + 1, updated_at = now()");
            }
            return new PublishOutcome(false, rows.size());
        });
    }

    private void upsertClaimAndEvidence(String workId, ClaimPayloadRow row) {
        List<Long> existing = existingEvidenceIds(workId, row.cause(), row.effect());
        if (existing.size() > 1) {
            throw new IllegalStateException("LEGACY_DUPLICATE_RELATION: 历史存在重复关系键，已拒绝覆盖");
        }
        long claimId = upsertClaim(row.claimCause(), row.claimEffect(), row.claimHash());
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO paper_claim_table (paper_id, claim_id, sign_of_impact, type_of_relationship,
                        claim, cause, cause_score, effect, effect_score, evidence, causal_inference_method,
                        evidence_method_other_description, is_main_contribution, level_of_tentativeness,
                        sources_of_exogenous_variation, statistical_significance)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, workId, claimId, row.signOfImpact(), row.typeOfRelationship(),
                    row.claim(), row.cause(), row.causeScore(), row.effect(), row.effectScore(), row.evidence(),
                    row.causalInferenceMethod(), row.evidenceMethodOtherDescription(), row.isMainContribution(),
                    row.levelOfTentativeness(), row.sourcesOfExogenousVariation(), row.statisticalSignificance());
        } else {
            jdbc.update("""
                    UPDATE paper_claim_table SET claim_id = ?, sign_of_impact = ?, type_of_relationship = ?,
                        claim = ?, cause_score = ?, effect_score = ?, evidence = ?, causal_inference_method = ?,
                        evidence_method_other_description = ?, is_main_contribution = ?, level_of_tentativeness = ?,
                        sources_of_exogenous_variation = ?, statistical_significance = ?
                    WHERE record_id = ? AND paper_id = ?
                    """, claimId, row.signOfImpact(), row.typeOfRelationship(), row.claim(),
                    row.causeScore(), row.effectScore(), row.evidence(), row.causalInferenceMethod(),
                    row.evidenceMethodOtherDescription(), row.isMainContribution(), row.levelOfTentativeness(),
                    row.sourcesOfExogenousVariation(), row.statisticalSignificance(), existing.get(0), workId);
        }
    }

    private long upsertClaim(String cause, String effect, String hash) {
        Long id = jdbc.queryForObject("""
                INSERT INTO claim_table (cause_standard, effect_standard, claim_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (claim_hash) DO UPDATE SET claim_hash = EXCLUDED.claim_hash
                RETURNING claim_id
                """, Long.class, cause, effect, hash);
        if (id == null) throw new IllegalStateException("无法获取 claim_id");
        return id;
    }

    private List<Long> existingEvidenceIds(String workId, String cause, String effect) {
        return jdbc.queryForList("""
                SELECT record_id FROM paper_claim_table
                WHERE paper_id = ? AND cause = ? AND effect = ? ORDER BY record_id ASC
                """, Long.class, workId, cause, effect);
    }
}
