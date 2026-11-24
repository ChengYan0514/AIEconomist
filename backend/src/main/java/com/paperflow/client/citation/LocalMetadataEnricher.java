package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationResult;

public interface LocalMetadataEnricher {
    CitationResult enrich(CitationResult result);
}
