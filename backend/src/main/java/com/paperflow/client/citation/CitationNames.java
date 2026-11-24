package com.paperflow.client.citation;

import java.util.Arrays;
import java.util.stream.Collectors;

final class CitationNames {
    private CitationNames() {}

    static String surname(String name) {
        int comma = name.indexOf(',');
        return comma >= 0 ? name.substring(0, comma).strip() : name.strip().replaceFirst("^.*\\s+", "");
    }

    static String initials(String name) {
        String stripped = name.strip();
        int comma = stripped.indexOf(',');
        String given = comma >= 0 ? stripped.substring(comma + 1)
                : stripped.substring(0, stripped.length() - surname(stripped).length());
        return Arrays.stream(given.strip().split("\\s+")).filter(token -> !token.isBlank())
                .map(token -> new String(Character.toChars(token.codePointAt(0))) + ".")
                .collect(Collectors.joining(" "));
    }

    static String surnameFirst(String name) {
        var initials = initials(name);
        return surname(name) + (initials.isEmpty() ? "" : ", " + initials);
    }

    static String initialsFirst(String name) {
        var initials = initials(name);
        return (initials.isEmpty() ? "" : initials + " ") + surname(name);
    }
}
