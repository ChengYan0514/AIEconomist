package com.paperflow.client.citation.model;

public record EvidenceStats(int judgedChunks, int supportedChunks, int supportedReferences, Rejected rejected,
                            boolean hitHardRecallLimit, boolean hitHardJudgmentLimit) {
    public record Rejected(int partial, int contradicts, int irrelevant, int lowScore, int lowConfidence) {}
}
