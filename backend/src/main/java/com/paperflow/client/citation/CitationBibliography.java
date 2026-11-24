package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationStyle;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.paperflow.client.citation.model.CitationIdentity.doi;

final class CitationBibliography {
    private CitationBibliography() {}

    static String format(CitationStyle style, List<CitationReference> references) {
        if (style == CitationStyle.IEEE) {
            return IntStream.range(0, references.size())
                    .mapToObj(index -> ieee(references.get(index), index + 1)).collect(Collectors.joining("\n"));
        }
        var byAuthor = Comparator.comparing((CitationReference reference) -> firstAuthor(reference).toLowerCase(Locale.ROOT));
        var order = style == CitationStyle.MLA ? byAuthor
                : byAuthor.thenComparing(CitationReference::year, Comparator.nullsLast(Comparator.naturalOrder()));
        return references.stream().sorted(order.thenComparing(CitationReference::title))
                .map(reference -> entry(style, reference)).collect(Collectors.joining("\n"));
    }

    static String firstAuthor(CitationReference reference) {
        return reference.authors().isEmpty() ? reference.title() : CitationNames.surname(reference.authors().get(0));
    }

    static String year(CitationReference reference) {
        return reference.year() == null ? "n.d." : reference.year().toString();
    }

    private static String entry(CitationStyle style, CitationReference ref) {
        if (style == CitationStyle.APA) {
            String authors = apaAuthors(ref.authors());
            String date = "(" + year(ref) + "). ";
            return authors + date + ref.title() + "." + venue(ref) + link(ref);
        }
        var authors = ref.authors().isEmpty() ? "" : String.join(", ", ref.authors()) + ". ";
        var title = "\"" + ref.title() + ".\"";
        if (style == CitationStyle.MLA) {
            return authors + title + venue(ref) + (ref.year() == null ? "" : " " + year(ref) + ".") + link(ref);
        }
        return authors + year(ref) + ". " + title + venue(ref) + link(ref);
    }

    private static String apaAuthors(List<String> authors) {
        var names = authors.stream().map(CitationNames::surnameFirst).toList();
        if (names.isEmpty()) return "";
        if (names.size() == 1) return sentenceEnd(names.get(0)) + " ";
        return sentenceEnd(String.join(", ", names.subList(0, names.size() - 1)) + ", & "
                + names.get(names.size() - 1)) + " ";
    }

    private static String ieee(CitationReference ref, int number) {
        var authors = ref.authors().stream().map(CitationNames::initialsFirst).collect(Collectors.joining(", "));
        return "[" + number + "] " + (authors.isEmpty() ? "" : authors + ", ") + "\"" + ref.title() + ",\" "
                + (ref.venue() == null || ref.venue().isBlank() ? "" : ref.venue() + ", ") + year(ref) + "."
                + (doi(ref.doi()) == null ? link(ref) : " doi: " + doi(ref.doi()));
    }

    private static String venue(CitationReference ref) {
        return ref.venue() == null || ref.venue().isBlank() ? "" : " *" + ref.venue() + "*.";
    }

    private static String link(CitationReference ref) {
        var doi = doi(ref.doi());
        if (doi != null) return " https://doi.org/" + doi;
        return ref.url() == null || ref.url().isBlank() ? "" : " " + ref.url();
    }

    private static String sentenceEnd(String value) {
        return value.endsWith(".") ? value : value + ".";
    }
}
