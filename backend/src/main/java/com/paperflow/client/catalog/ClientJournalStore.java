package com.paperflow.client.catalog;

import java.util.List;

public interface ClientJournalStore {
    Catalog search(String query, int limit);

    record Journal(String sourceId, String name, long paperCount, long publishedCount, Integer yearMin, Integer yearMax) {}
    record Catalog(List<Journal> items, long total) {}
}
