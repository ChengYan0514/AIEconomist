package com.paperflow.client.citation;

import com.paperflow.client.citation.model.CitationReference;
import com.paperflow.client.citation.model.CitationResult;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

import static com.paperflow.client.citation.model.CitationIdentity.doi;
import static com.paperflow.client.citation.model.CitationIdentity.key;

public final class BibtexExporter {
    public String text(CitationFormatter.Output output) {
        return output.annotatedText() + "\n\nReferences\n" + output.bibliography();
    }

    public String markdown(String title, CitationFormatter.Output output) {
        return "# " + title.replaceAll("\\R", " ") + "\n\n## 正文\n\n" + output.annotatedText()
                + "\n\n## 参考文献\n\n" + output.bibliography();
    }

    public String bibtex(CitationResult result) {
        var references = new LinkedHashMap<String, CitationReference>();
        result.statements().forEach(statement -> result.selected(statement)
                .forEach(reference -> references.putIfAbsent(key(reference), reference)));
        var entries = new ArrayList<String>();
        var keys = new HashSet<String>();
        references.values().forEach(reference -> entries.add(entry(reference, uniqueKey(reference, keys))));
        return String.join("\n\n", entries);
    }

    private String uniqueKey(CitationReference reference, Set<String> keys) {
        String author = Normalizer.normalize(CitationBibliography.firstAuthor(reference), Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String base = (author.isEmpty() ? "ref" : author) + (reference.year() == null ? "nd" : reference.year());
        String citeKey = base;
        for (int suffix = 2; !keys.add(citeKey); suffix++) citeKey = base + "-" + suffix;
        return citeKey;
    }

    private String entry(CitationReference reference, String key) {
        var fields = new ArrayList<String>();
        field(fields, "author", String.join(" and ", reference.authors()));
        field(fields, "title", reference.title());
        field(fields, "journal", reference.venue());
        field(fields, "year", reference.year() == null ? null : reference.year().toString());
        field(fields, "doi", doi(reference.doi()));
        field(fields, "url", reference.url());
        return "@article{" + key + ",\n" + String.join(",\n", fields) + "\n}";
    }

    private void field(ArrayList<String> fields, String name, String value) {
        if (value == null || value.isBlank()) return;
        var escaped = value.replace("\\", "\\textbackslash\u0001")
                .replaceAll("([{}&%$#_])", "\\\\$1").replace("\u0001", "{}")
                .replace("~", "\\textasciitilde{}").replace("^", "\\textasciicircum{}")
                .replaceAll("\\R", " ");
        fields.add("  " + name + " = {" + escaped + "}");
    }
}
