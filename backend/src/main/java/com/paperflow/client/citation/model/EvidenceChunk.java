package com.paperflow.client.citation.model;

public record EvidenceChunk(String chunkId, String sectionTitle, String text, Double retrievalScore,
                            Double supportScore, Double confidence, String reason) {}
