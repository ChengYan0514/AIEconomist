package com.paperflow.causal.model;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SignCategory {
    public static final String POSITIVE = "positive";
    public static final String NEGATIVE = "negative";
    public static final String NULL = "null";
    public static final String MIXED = "mixed";
    private static final Set<String> NULL_ALIASES = Set.of("null_efffect", "null_effect", "null effect", "null");

    private SignCategory() {}

    public static String classify(String raw) {
        if (raw == null) return MIXED;
        String value = raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (POSITIVE.equals(value)) return POSITIVE;
        if (NEGATIVE.equals(value)) return NEGATIVE;
        if (NULL_ALIASES.contains(value)) return NULL;
        return MIXED;
    }

    public static Map<String, Long> breakdown(long positive, long negative, long nullCount, long mixed) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(POSITIVE, positive);
        counts.put(NEGATIVE, negative);
        counts.put(NULL, nullCount);
        counts.put(MIXED, mixed);
        return counts;
    }

    public static String dominant(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(MIXED);
    }

    public static double disagreement(long total, Map<String, Long> counts, String dominant) {
        if (total <= 0) return 0.0;
        long top = counts.getOrDefault(dominant, 0L);
        return 1.0 - ((double) top / total);
    }
}
