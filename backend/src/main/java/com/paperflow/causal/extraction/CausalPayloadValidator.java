package com.paperflow.causal.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.causal.extraction.CausalExtractionModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class CausalPayloadValidator {
    private static final Map<String, Integer> BOUNDS = Map.of(
            "paper_id", 100, "claim_cause", 255, "claim_effect", 255, "sign_of_impact", 50,
            "type_of_relationship", 100, "causal_inference_method", 100,
            "level_of_tentativeness", 50, "statistical_significance", 100);
    private final ObjectMapper json;

    public CausalPayloadValidator(ObjectMapper json) {
        this.json = json;
    }

    public List<EditableClaimItem> toEditableClaims(List<ClaimPayloadRow> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<EditableClaimItem> list = new ArrayList<>();
        int seq = 1;
        for (ClaimPayloadRow row : rows) {
            String claimId = row.claimHash() != null && !row.claimHash().isBlank() ? row.claimHash() : "claim-" + (seq++);
            list.add(new EditableClaimItem(
                    claimId, row.cause(), row.effect(), row.typeOfRelationship(),
                    row.signOfImpact(), row.isMainContribution(), row.statisticalSignificance(),
                    row.causalInferenceMethod(), row.sourcesOfExogenousVariation(),
                    row.levelOfTentativeness(), row.evidenceMethodOtherDescription(), row.evidence()
            ));
        }
        return list;
    }

    public ValidatedResult parseAndValidateEditedClaims(String workId, List<EditableClaimItem> items, RunMetrics originalMetrics) {
        List<ClaimPayloadRow> rows = new ArrayList<>();
        if (items != null) {
            for (EditableClaimItem item : items) {
                if (item.cause() == null || item.cause().isBlank() || item.effect() == null || item.effect().isBlank()) {
                    continue; // Skip blank rows
                }
                String cause = item.cause().strip();
                String effect = item.effect().strip();
                String hash = claimHash(cause, effect);
                String claimText = (item.evidence() != null && !item.evidence().isBlank()) ? item.evidence() : (cause + " -> " + effect);
                rows.add(new ClaimPayloadRow(
                        workId, cause, effect, hash, claimText, cause, effect,
                        Objects.requireNonNullElse(item.signOfImpact(), "positive"),
                        Objects.requireNonNullElse(item.typeOfRelationship(), "direct effect"),
                        Objects.requireNonNullElse(item.evidence(), ""),
                        Objects.requireNonNullElse(item.causalInferenceMethod(), "Other"),
                        item.evidenceMethodOtherDescription(),
                        item.isMainContribution(),
                        item.levelOfTentativeness(),
                        item.sourcesOfExogenousVariation(),
                        item.statisticalSignificance(),
                        null, null
                ));
            }
        }
        RunMetrics metrics = new RunMetrics(
                rows.size(),
                originalMetrics != null ? originalMetrics.collisionCount() : 0,
                originalMetrics != null ? originalMetrics.callsUsed() : 0,
                originalMetrics != null ? originalMetrics.tokensUsed() : 0,
                originalMetrics != null ? originalMetrics.modelVersions() : List.of()
        );
        String payloadHash = digest("SHA-256", workId + "::" + rows.size() + "::" + System.currentTimeMillis());
        return new ValidatedResult(rows, payloadHash, metrics);
    }

    public String inputHash(String paperId, String workId, String sourceId, List<BlockExportRow> blocks) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("paperId", paperId);
            canonical.put("workId", workId);
            canonical.put("sourceId", sourceId);
            canonical.put("blocks", blocks);
            return digest("SHA-256", json.writeValueAsString(canonical));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot hash causal input blocks", error);
        }
    }

    public ValidatedResult parseAndValidate(String expectedWorkId, String resultJson) {
        try {
            JsonNode root = json.readTree(resultJson);
            if (!expectedWorkId.equals(root.path("workId").asText()) || !root.path("rows").isArray()) {
                throw new IllegalArgumentException("INVALID_RESULT: workId 或 rows 无效");
            }
            List<ClaimPayloadRow> rows = new ArrayList<>();
            for (JsonNode row : root.path("rows")) rows.add(validateRow(expectedWorkId, row));
            RunMetrics metrics = new RunMetrics(
                    root.path("claimCount").asInt(rows.size()),
                    root.path("collisionCount").asInt(0),
                    root.path("calls").asInt(0),
                    root.path("tokens").asInt(0),
                    readStrings(root.path("modelVersions")));
            return new ValidatedResult(rows, digest("SHA-256", resultJson), metrics);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("INVALID_RESULT: 结果 JSON 无法解析", error);
        }
    }

    private ClaimPayloadRow validateRow(String expectedWorkId, JsonNode row) {
        String paperId = required(row, "paper_id");
        if (!expectedWorkId.equals(paperId)) throw new IllegalArgumentException("INVALID_RESULT: 存在越界作品主张");
        String claimCause = required(row, "claim_cause");
        String claimEffect = required(row, "claim_effect");
        String claimHash = required(row, "claim_hash");
        if (!claimHash.matches("[a-f0-9]{32}") || (isAscii(claimCause) && isAscii(claimEffect)
                && !claimHash.equals(claimHash(claimCause, claimEffect)))) {
            throw new IllegalArgumentException("INVALID_RESULT: claim_hash 校验失败");
        }
        for (var entry : BOUNDS.entrySet()) {
            String value = optional(row, entry.getKey());
            if (value != null && value.codePointCount(0, value.length()) > entry.getValue()) {
                throw new IllegalArgumentException("INVALID_RESULT: 字段超长 " + entry.getKey());
            }
        }
        if (!row.has("is_main_contribution") || !row.get("is_main_contribution").isBoolean()) {
            throw new IllegalArgumentException("INVALID_RESULT: is_main_contribution 必须为布尔值");
        }
        return new ClaimPayloadRow(paperId, claimCause, claimEffect, claimHash,
                required(row, "claim"), required(row, "cause"), required(row, "effect"),
                required(row, "sign_of_impact"), required(row, "type_of_relationship"),
                required(row, "evidence"), required(row, "causal_inference_method"),
                optional(row, "evidence_method_other_description"), row.get("is_main_contribution").asBoolean(),
                optional(row, "level_of_tentativeness"), optional(row, "sources_of_exogenous_variation"),
                optional(row, "statistical_significance"), score(row, "cause_score"), score(row, "effect_score"));
    }

    public static String claimHash(String cause, String effect) {
        return digest("MD5", cause.strip().toLowerCase(Locale.ROOT) + "||" + effect.strip().toLowerCase(Locale.ROOT));
    }

    public static String digest(String algorithm, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String required(JsonNode row, String field) {
        String value = optional(row, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("INVALID_RESULT: 缺少字段 " + field);
        return value;
    }

    private static String optional(JsonNode row, String field) {
        JsonNode node = row.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static BigDecimal score(JsonNode row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            BigDecimal number = new BigDecimal(node.asText()).setScale(3, RoundingMode.HALF_UP);
            if (number.abs().compareTo(BigDecimal.TEN) >= 0) throw new IllegalArgumentException("INVALID_RESULT: 分数越界 " + field);
            return number;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("INVALID_RESULT: 分数无效 " + field);
        }
    }

    private static List<String> readStrings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual()) list.add(item.asText()); });
        return List.copyOf(list);
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < 128);
    }

    public record ValidatedResult(List<ClaimPayloadRow> rows, String payloadHash, RunMetrics metrics) {}
}
