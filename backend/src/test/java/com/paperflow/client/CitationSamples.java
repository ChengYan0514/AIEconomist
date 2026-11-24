package com.paperflow.client;

import com.paperflow.client.citation.model.CitationReference;

import java.util.List;

final class CitationSamples {
    static CitationReference reference(String id, String title, int year, String... authors) {
        return new CitationReference(id, null, null, null, title, List.of(authors), year, "Journal",
                null, null, CitationReference.MetadataSource.ENGINE, null, null, null, List.of());
    }
}
