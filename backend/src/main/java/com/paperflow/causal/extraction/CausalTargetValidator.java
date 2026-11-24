package com.paperflow.causal.extraction;

import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.model.IngestionMode;

import java.util.*;

final class CausalTargetValidator {
    private final int maxBatchTargets;

    CausalTargetValidator(int maxBatchTargets) {
        this.maxBatchTargets = maxBatchTargets;
    }

    TargetSpec validate(TargetSpec target) {
        if (target == null || target.mode() == null) throw new IllegalArgumentException("必须指定抽取目标模式");
        if (target.mode() != TargetMode.FILTER) {
            List<String> ids = switch (target.mode()) {
                case PAPER_IDS -> target.paperIds();
                case WORK_IDS -> target.workIds();
                case SOURCE_IDS -> target.sourceIds();
                case FILTER -> List.of();
            };
            if (ids == null || ids.isEmpty() || ids.size() > maxBatchTargets) {
                throw new IllegalArgumentException("目标 ID 数量必须在 1 到 " + maxBatchTargets + " 之间");
            }
        } else if (target.filters() != null && target.filters().yearFrom() != null
                && target.filters().yearTo() != null && target.filters().yearFrom() > target.filters().yearTo()) {
            throw new IllegalArgumentException("起始年份不能大于结束年份");
        }
        return target;
    }

    List<TargetCandidate> enforceCap(List<TargetCandidate> rows) {
        if (rows.size() > maxBatchTargets) {
            throw new IllegalArgumentException("目标论文超过单批上限 " + maxBatchTargets + " 篇，请缩小筛选范围");
        }
        return rows;
    }

    Map<String, Object> scopeSummary(TargetSpec target, int total) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", target.mode().name());
        map.put("targetCount", total);
        if (target.sourceIds() != null && !target.sourceIds().isEmpty()) map.put("sourceIds", target.sourceIds());
        if (target.filters() != null) {
            if (target.filters().sourceId() != null) map.put("sourceId", target.filters().sourceId());
            if (target.filters().yearFrom() != null) map.put("yearFrom", target.filters().yearFrom());
            if (target.filters().yearTo() != null) map.put("yearTo", target.filters().yearTo());
        }
        return map;
    }

    Map<String, Object> configSnapshot(EngineHealth health, IngestionMode mode, int maxCalls) {
        return Map.of("profileVersion", health.profileVersion(),
                "chatModel", Objects.toString(health.chatModel(), ""),
                "embeddingModel", Objects.toString(health.embeddingModel(), ""),
                "maxCalls", maxCalls, "ingestionMode", mode.name());
    }

    static String skipMessage(String reason) {
        return switch (reason) {
            case "ARCHIVED" -> "论文已归档";
            case "UNMATCHED" -> "尚未关联 OpenAlex 作品";
            case "NOT_PUBLISHED" -> "尚未发布结构化全文";
            case "ACTIVE_EXTRACTION" -> "该作品已有活跃抽取任务";
            default -> reason;
        };
    }
}
