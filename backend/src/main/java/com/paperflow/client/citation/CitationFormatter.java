package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;
import com.paperflow.client.citation.model.CitationStatement;
import com.paperflow.client.citation.model.CitationStyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.paperflow.client.citation.model.CitationIdentity.key;

public final class CitationFormatter {
    public record Output(String annotatedText, String bibliography) {}

    public Output format(String original, CitationStyle style, CitationResult result) {
        var insertions = new ArrayList<Insertion>();
        for (var statement : result.statements()) {
            var selected = result.selected(statement);
            if (!selected.isEmpty()) {
                int offset = insertionOffset(original, statement);
                if (offset >= 0) insertions.add(new Insertion(offset, selected));
            }
        }
        insertions.sort(Comparator.comparingInt(Insertion::offset));
        var references = new LinkedHashMap<String, CitationReference>();
        var numbers = new LinkedHashMap<String, Integer>();
        var text = new StringBuilder(original);
        var marks = insertions.stream().map(insertion -> mark(style, insertion, references, numbers)).toList();
        for (int index = marks.size() - 1; index >= 0; index--) {
            text.insert(insertions.get(index).offset(), " " + marks.get(index));
        }
        return new Output(text.toString(), CitationBibliography.format(style, List.copyOf(references.values())));
    }

    private String mark(CitationStyle style, Insertion insertion, Map<String, CitationReference> references,
                        Map<String, Integer> numbers) {
        var unique = new LinkedHashMap<String, CitationReference>();
        for (var reference : insertion.references()) {
            var key = key(reference);
            unique.putIfAbsent(key, reference);
            references.putIfAbsent(key, reference);
            numbers.computeIfAbsent(key, ignored -> numbers.size() + 1);
        }
        if (style == CitationStyle.IEEE) {
            var ordered = new TreeSet<Integer>();
            unique.keySet().forEach(key -> ordered.add(numbers.get(key)));
            return "[" + ranges(List.copyOf(ordered)) + "]";
        }
        return unique.values().stream().map(reference -> authorDate(style, reference))
                .collect(Collectors.joining("; ", "(", ")"));
    }

    private String ranges(List<Integer> numbers) {
        var groups = new ArrayList<String>();
        for (int start = 0; start < numbers.size();) {
            int end = start;
            while (end + 1 < numbers.size() && numbers.get(end + 1) == numbers.get(end) + 1) end++;
            if (end - start >= 2) {
                groups.add(numbers.get(start) + "–" + numbers.get(end));
            } else {
                for (int index = start; index <= end; index++) groups.add(numbers.get(index).toString());
            }
            start = end + 1;
        }
        return String.join(", ", groups);
    }

    private String authorDate(CitationStyle style, CitationReference reference) {
        var authors = reference.authors();
        var first = CitationBibliography.firstAuthor(reference);
        var separator = style == CitationStyle.APA ? " & " : " and ";
        var names = switch (authors.size()) {
            case 0, 1 -> first;
            case 2 -> first + separator + CitationNames.surname(authors.get(1));
            default -> first + " et al.";
        };
        return switch (style) {
            case APA -> names + ", " + CitationBibliography.year(reference);
            case CHICAGO_AUTHOR_DATE -> names + " " + CitationBibliography.year(reference);
            default -> names;
        };
    }

    private int insertionOffset(String original, CitationStatement statement) {
        var sentence = statement.text().strip();
        if (sentence.isEmpty()) return -1;
        int start = statement.startChar();
        int end = statement.endChar();
        if (start < 0 || end <= start || end > original.length()
                || !original.substring(start, end).strip().equals(sentence)) {
            start = original.indexOf(sentence);
            if (start < 0) return -1;
            end = start + sentence.length();
        }
        while (end > start && Character.isWhitespace(original.charAt(end - 1))) end--;
        if (end > start && ".!?。！？".indexOf(original.charAt(end - 1)) >= 0) return end - 1;
        return end;
    }

    private record Insertion(int offset, List<CitationReference> references) {}
}
