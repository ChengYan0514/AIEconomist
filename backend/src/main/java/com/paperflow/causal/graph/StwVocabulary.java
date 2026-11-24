package com.paperflow.causal.graph;

import java.util.*;

public final class StwVocabulary {
    private record Entry(String label, String lower) {}

    private final List<Entry> entries;

    public StwVocabulary(Collection<String> labels) {
        var unique = new TreeSet<String>();
        if (labels != null) {
            for (String raw : labels) {
                if (raw == null) continue;
                String clean = raw.strip();
                if (!clean.isEmpty()) unique.add(clean);
            }
        }
        this.entries = unique.stream()
                .map(label -> new Entry(label, label.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public int size() {
        return entries.size();
    }

    public List<String> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<String> prefix = new ArrayList<>(Math.min(limit, 16));
        List<String> contains = new ArrayList<>(Math.min(limit, 16));
        for (Entry entry : entries) {
            if (entry.lower().startsWith(needle)) {
                prefix.add(entry.label());
                if (prefix.size() >= limit) break;
            } else if (contains.size() < limit && entry.lower().contains(needle)) {
                contains.add(entry.label());
            }
        }
        int remaining = limit - prefix.size();
        if (remaining > 0 && !contains.isEmpty()) {
            prefix.addAll(contains.subList(0, Math.min(remaining, contains.size())));
        }
        return List.copyOf(prefix);
    }
}
