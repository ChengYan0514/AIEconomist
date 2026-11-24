package com.paperflow.client.adapters.engine;

import com.paperflow.client.citation.CitationEngine;
import com.paperflow.client.citation.model.CitationResult;

public final class DisabledCitationEngine implements CitationEngine {
    @Override
    public String providerId() {
        return "disabled";
    }

    @Override
    public boolean isHealthy() {
        return false;
    }

    @Override
    public CitationResult execute(EngineRequest request, ProgressSink sink, CancellationSignal cancelled) {
        throw new EngineFailure("CITATION_ENGINE_UNAVAILABLE", "参考文献自动标注引擎未启用");
    }
}
