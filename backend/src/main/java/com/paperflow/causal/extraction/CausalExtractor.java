package com.paperflow.causal.extraction;

import com.paperflow.causal.extraction.CausalExtractionModels.*;
import com.paperflow.causal.model.IngestionMode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface CausalExtractor {
    EngineHealth health();
    String submit(UUID runId, String workId, IngestionMode mode, int maxCalls, String resumeEngineRunId,
                  List<BlockExportRow> blocks) throws IOException, InterruptedException;
    EngineRunStatus poll(String engineRunId) throws IOException, InterruptedException;
    void cancel(String engineRunId) throws IOException, InterruptedException;
    List<ArtifactInfo> artifacts(String engineRunId) throws IOException, InterruptedException;
    InputStream openArtifact(String engineRunId, String name) throws IOException, InterruptedException;
}
